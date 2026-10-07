// Run against an isolated stage with synthetic data, never against production.
// node scripts/check-artwork-browser.mjs (credentials: .stage/e2e-credentials.json)
import { chromium } from '../frontend/node_modules/playwright/index.mjs';
import { readFile, mkdir } from 'node:fs/promises';
import assert from 'node:assert/strict';

const base = 'http://localhost:11000';
const credentials = JSON.parse(await readFile(new URL('../.stage/e2e-credentials.json', import.meta.url)));
const browser = await chromium.launch({ headless: true });
const errors = [];
const contexts = [];
async function context() {
  const ctx = await browser.newContext({ baseURL: base });
  contexts.push(ctx);
  ctx.on('page', page => page.on('pageerror', error => errors.push(error.message)));
  return ctx;
}
async function call(ctx, path, method = 'GET', data) {
  const session = await (await ctx.request.get('/api/auth/session')).json();
  const response = await ctx.request.fetch(path, {
    method, data, headers: { [session.csrfHeader]: session.csrfToken },
  });
  assert.ok(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`);
  return response.json();
}
async function visible(locator) { await locator.waitFor({ state: 'visible', timeout: 20_000 }); }
async function recipient(delivery, email) {
  const ctx = await context();
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', { options: {
    protocol: 'ctap2', transport: 'internal', hasResidentKey: true,
    hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true,
  } });
  const path = new URL(delivery.recipient.url).pathname;
  await page.goto(base + path);
  const contact = page.getByLabel('받으신 이메일');
  await visible(contact);
  await contact.fill(email);
  await page.getByRole('button', { name: '패스키로 수신 확정하기', exact: true }).click();
  await visible(page.locator('.content-view .artwork-status').filter({ hasText: '미판매' }));
  // Reload and exercise an assertion with the newly registered passkey as well.
  await page.reload();
  await page.getByRole('button', { name: '내 패스키로 링크 열기', exact: true }).click();
  await visible(page.locator('.content-view .artwork-status').filter({ hasText: '미판매' }));
  return { ctx, page, path };
}

try {
  const admin = await context();
  await call(admin, '/api/auth/login', 'POST', credentials);
  const stamp = Date.now();
  const title = `Browser artwork ${stamp}`;
  const source = await call(admin, '/api/contents', 'POST', {
    title: `Browser catalog ${stamp}`,
    body: { artworks: [{ title, artist: 'Test artist', price: '100' }, { title: `${title} B`, price: '200' }] },
  });
  const artworkId = Number(source.body.artworks[0].artworkId);
  const deliveries = [];
  for (const label of ['one', 'two']) {
    deliveries.push(await call(admin, '/api/links/artworks', 'POST', {
      artworkIds: [artworkId], email: `${label}-${stamp}@example.com`, title: `Browser ${label}`, notify: false,
    }));
  }
  const readers = [];
  for (let i = 0; i < 2; i++) readers.push(await recipient(deliveries[i], `${i ? 'two' : 'one'}-${stamp}@example.com`));
  console.log('PASS: two recipients registered passkeys and authenticated again after reload.');
  const operator = await admin.newPage();
  operator.on('response', response => {
    if (response.request().method() === 'PATCH' && !response.ok()) {
      console.error(`Status update failed: ${response.status()}`);
    }
  });
  operator.on('dialog', dialog => dialog.accept());
  await operator.goto(`${base}/admin/artworks?search=${encodeURIComponent(title)}`);
  const selector = operator.getByLabel(`${title} 판매 상태`, { exact: true });
  for (const [value, text] of [['hold', '대기'], ['sold', '판매 완료'], ['', '미판매']]) {
    await selector.selectOption(value);
    await visible(operator.getByText('판매 상태를 변경했습니다. 연결된 링크에도 반영됩니다.', { exact: true }))
      .catch(async error => { console.error(await operator.locator('section').innerText()); throw error; });
    for (const reader of readers) {
      await visible(reader.page.locator('.content-view .artwork-status').filter({ hasText: text }));
    }
    await operator.waitForFunction(() => !document.querySelector('select[aria-label$="판매 상태"]')?.disabled);
  }
  console.log('PASS: admin UI changes reached both recipient browsers over SSE.');
  await mkdir(new URL('../.stage/screenshots/', import.meta.url), { recursive: true });
  await operator.screenshot({ path: new URL('../.stage/screenshots/artwork-admin.png', import.meta.url).pathname, fullPage: true });
  const edited = { ...source.body, artworks: [...source.body.artworks].reverse() };
  edited.artworks[1] = { ...edited.artworks[1], title: 'Updated original', price: '999' };
  const saved = await call(admin, `/api/contents/${source.id}`, 'PUT', { title: source.title, body: edited });
  assert.equal(Number(saved.body.artworks[1].artworkId), artworkId);
  await call(admin, `/api/admin/artworks/${artworkId}/status`, 'PATCH', { saleStatus: 'sold' });
  for (const reader of readers) {
    await visible(reader.page.locator('.content-view .artwork-status').filter({ hasText: '판매 완료' }));
    await visible(reader.page.locator('.content-view').getByText(title, { exact: true }));
    assert.equal(await reader.page.locator('.content-view').getByText('Updated original', { exact: true }).count(), 0);
  }
  await readers[1].page.screenshot({ path: new URL('../.stage/screenshots/artwork-recipient.png', import.meta.url).pathname, fullPage: true });
  await call(admin, `/api/links/${deliveries[0].id}/recipients/${deliveries[0].recipient.id}/status`, 'POST', { revoked: true });
  await visible(readers[0].page.getByRole('alert').filter({ hasText: '권한이 만료' }));
  assert.equal(await readers[0].page.locator('.content-view').count(), 0);
  await call(admin, `/api/admin/artworks/${artworkId}/status`, 'PATCH', { saleStatus: 'hold' });
  await visible(readers[1].page.locator('.content-view .artwork-status').filter({ hasText: '대기' }));
  assert.equal(await readers[0].page.locator('.content-view').count(), 0);
  const stranger = await context();
  const statusUrl = `/api/links/s/${deliveries[1].recipient.shortCode}/artwork-status/events`;
  assert.equal((await stranger.request.get(statusUrl)).status(), 403);
  assert.equal((await stranger.request.get('/api/admin/artworks')).status(), 401);
  assert.deepEqual(errors, []);
  console.log('PASS: real HTTP login; two WebAuthn registrations and assertions; admin UI status changes; SSE in two browsers; stable IDs and immutable snapshots; recipient revocation; anonymous access denied.');
} finally {
  for (const ctx of contexts) await ctx.close();
  await browser.close();
}
