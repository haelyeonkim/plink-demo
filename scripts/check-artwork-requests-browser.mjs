// Isolated, synthetic-data browser check. Requires the built JAR and Vite on :13002
// forwarding to :18082. It never reads .env or connects to an existing database.
import { chromium } from '../frontend/node_modules/playwright/index.mjs';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdir, readdir, readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { setTimeout as pause } from 'node:timers/promises';

const root = new URL('../', import.meta.url).pathname;
const base = 'http://localhost:13002';
const password = randomUUID();
const jar = (await readdir(`${root}target`)).find(name => name.endsWith('.jar'));
assert.ok(jar, 'Build the JAR first.');
const server = spawn('java', ['-jar', `${root}target/${jar}`, '--server.port=18082', '--spring.config.import=',
  '--spring.datasource.url=jdbc:h2:mem:artworkbrowser;DB_CLOSE_DELAY=-1', '--spring.datasource.username=sa', '--spring.datasource.password=',
  '--spring.flyway.locations=classpath:db/migration', `--plink.auth.base-url=${base}`, '--plink.admin.email=qa@example.test',
  '--plink.auth.google-client-id='], {
  cwd: root, env: { ...process.env, PLINK_ADMIN_PASSWORD: password }, stdio: ['ignore', 'pipe', 'pipe'],
});
let output = '';
server.stdout.on('data', data => { output += data.toString(); });
server.stderr.on('data', data => { output += data.toString(); });
let browser;
async function screenshot(page, name) {
  // Optional QA font for minimal Linux images; does not change application assets.
  if (process.env.QA_FONT_PATH) {
    const font = (await readFile(process.env.QA_FONT_PATH)).toString('base64');
    await page.addStyleTag({ content: `@font-face { font-family: QaKorean; src: url(data:font/otf;base64,${font}) } body, button, input, select, textarea { font-family: QaKorean, sans-serif !important; }` });
    await page.evaluate(() => document.fonts.ready);
  }
  await page.screenshot({ path: `${root}docs/screenshots/${name}.png`, fullPage: true });
}
const errors = [];
async function call(ctx, path, method = 'GET', data) {
  const auth = await (await ctx.request.get(`${base}/api/auth/session`)).json();
  const response = await ctx.request.fetch(`${base}${path}`, { method, data, headers: { [auth.csrfHeader]: auth.csrfToken } });
  assert.ok(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`);
  return response.json();
}
try {
  for (let i = 0; i < 90 && !output.includes('Started PlinkApplication'); i++) {
    if (server.exitCode !== null) throw new Error(`Isolated server exited: ${output.slice(-3000)}`);
    await pause(1000);
  }
  assert.ok(output.includes('Started PlinkApplication'), 'Isolated server did not start');
  browser = await chromium.launch({ headless: true });
  const admin = await browser.newContext({ baseURL: base });
  await call(admin, '/api/auth/login', 'POST', { email: 'qa@example.test', password });
  const content = await call(admin, '/api/contents', 'POST', { title: '수신자 액션 검증', body: {
    intro: '관심 있는 작품의 홀딩 또는 구매 상담을 요청해 주세요.',
    artworks: [{ title: 'Blue Field', artist: 'Test Artist', year: '2026', medium: 'Oil on canvas', price: '1000' }],
  } });
  const artworkId = Number(content.body.artworks[0].artworkId);
  const email = 'recipient@example.test';
  const delivery = await call(admin, '/api/links/artworks', 'POST', { artworkIds: [artworkId], email, title: '선택 작품', notify: false });
  const recipient = await browser.newContext({ baseURL: base, viewport: { width: 390, height: 844 } });
  const page = await recipient.newPage(); page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  const cdp = await recipient.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', { options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true,
    hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true } });
  await page.goto(new URL(delivery.recipient.url).pathname);
  await page.getByLabel('받으신 이메일').fill(email);
  await page.getByRole('button', { name: '패스키로 수신 확정하기', exact: true }).click();
  await page.getByLabel('이메일 인증번호').waitFor();
  const codes = [...output.matchAll(/인증번호: (\d{6})/g)];
  assert.ok(codes.length, 'Synthetic verification code was not generated');
  await page.getByLabel('이메일 인증번호').fill(codes.at(-1)[1]);
  await page.getByRole('button', { name: '패스키로 수신 확정하기', exact: true }).click();
  await page.getByRole('button', { name: '관심 있음', exact: true }).click();
  await page.getByRole('button', { name: '관심 해제', exact: true }).waitFor();
  await page.getByRole('button', { name: '홀딩 요청', exact: true }).click();
  await page.locator('.request-status').filter({ hasText: '홀딩 승인 대기' }).waitFor();
  const operator = await admin.newPage(); operator.on('pageerror', error => errors.push(error.message));
  await operator.goto('/admin/artworks/requests');
  const panel = operator.locator('.request-panel form');
  // A refusal is explained to the recipient, and only an inquiry the administrator allows reopens it.
  await operator.getByRole('button', { name: '거절', exact: true }).click();
  await panel.locator('select').selectOption('전시·대여 일정으로 홀딩이 어려워요');
  await operator.getByRole('button', { name: '처리 확인', exact: true }).click();
  await page.locator('.request-notices').filter({ hasText: '전시·대여 일정으로 홀딩이 어려워요' }).waitFor({ timeout: 25000 });
  await page.getByRole('button', { name: '홀딩 요청', exact: true }).click();
  const refusedHold = page.getByRole('alertdialog');
  await refusedHold.getByText('홀딩 요청이 거절된 상태예요').waitFor();
  assert.ok((await refusedHold.innerText()).includes('전시·대여 일정으로 홀딩이 어려워요'));
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, 'Refusal popup overflows');
  await refusedHold.getByLabel('문의 내용').fill('일정이 바뀌어 다시 요청드리고 싶어요.');
  await refusedHold.getByRole('button', { name: '담당자에게 문의', exact: true }).click();
  await page.locator('.request-status').filter({ hasText: '재요청 문의 중' }).waitFor();
  await operator.getByRole('button', { name: '새로고침', exact: true }).click();
  await operator.locator('.request-count').filter({ hasText: '재요청 문의' }).filter({ hasText: '1건' }).waitFor();
  await operator.getByText('문의: 일정이 바뀌어 다시 요청드리고 싶어요.', { exact: true }).waitFor();
  await operator.getByRole('button', { name: '재요청 허용', exact: true }).click();
  await operator.getByRole('button', { name: '처리 확인', exact: true }).click();
  await page.locator('.request-notices').filter({ hasText: '재요청 허용' }).waitFor({ timeout: 25000 });
  await page.getByRole('button', { name: '홀딩 요청', exact: true }).click();
  await page.locator('.request-status').filter({ hasText: '홀딩 승인 대기' }).waitFor();
  await operator.getByRole('button', { name: '새로고침', exact: true }).click();
  await operator.getByRole('button', { name: '승인', exact: true }).click();
  const deadline = new Date(Date.now() + 3600000).toISOString().slice(0, 16);
  await operator.getByLabel('홀딩 종료 시각 (현재 기기 시간대)').fill(deadline);
  await operator.getByRole('button', { name: '처리 확인', exact: true }).click();
  await operator.getByRole('button', { name: '홀딩 해제', exact: true }).waitFor();
  await page.locator('.request-status').filter({ hasText: '홀딩 확정' }).waitFor({ timeout: 25000 });
  // Extending keeps the same hold and tells the recipient why.
  const approvedUntil = await page.locator('.artwork-request p').filter({ hasText: '홀딩 기한:' }).innerText();
  await operator.getByRole('button', { name: '기한 연장', exact: true }).click();
  await panel.getByRole('button', { name: '+3일', exact: true }).click();
  await panel.locator('select').selectOption('고객 요청으로 기한을 연장했어요');
  await operator.getByRole('button', { name: '처리 확인', exact: true }).click();
  await page.locator('.request-notices').filter({ hasText: '고객 요청으로 기한을 연장했어요' }).waitFor({ timeout: 25000 });
  assert.notEqual(await page.locator('.artwork-request p').filter({ hasText: '홀딩 기한:' }).innerText(), approvedUntil);
  assert.ok((await page.locator('.request-status').innerText()).includes('홀딩 확정'));
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, 'Mobile page overflows');
  await mkdir(`${root}docs/screenshots`, { recursive: true });
  await screenshot(page, 'recipient-artwork-inbox');
  await page.getByRole('button', { name: '구매 의사 전달', exact: true }).click();
  await page.getByRole('button', { name: '구매 의사 철회', exact: true }).click();
  assert.equal(await page.getByLabel('홀딩도 함께 취소').isChecked(), false);
  await page.getByRole('button', { name: '철회 확인', exact: true }).click();
  await page.getByRole('button', { name: '구매 의사 전달', exact: true }).waitFor();
  assert.ok((await page.locator('.request-status').innerText()).includes('홀딩 확정'));
  await page.getByRole('button', { name: '구매 의사 전달', exact: true }).click();
  await operator.getByRole('button', { name: '새로고침', exact: true }).click();
  await operator.getByRole('button', { name: '판매 확정', exact: true }).click();
  await operator.getByRole('button', { name: '처리 확인', exact: true }).click();
  await page.getByRole('button', { name: '판매 취소 요청', exact: true }).waitFor({ timeout: 25000 });
  await page.getByRole('button', { name: '판매 취소 요청', exact: true }).click();
  await page.getByLabel('취소 요청 사유').fill('구매 일정 변경');
  await page.getByRole('button', { name: '취소 요청 전달', exact: true }).click();
  await page.locator('.request-status').filter({ hasText: '판매 취소 요청' }).waitFor();
  await operator.getByRole('button', { name: '새로고침', exact: true }).click();
  await operator.getByRole('button', { name: '판매 취소 거절', exact: true }).click();
  await panel.locator('select').selectOption('배송·설치가 이미 진행되었어요');
  await operator.getByRole('button', { name: '처리 확인', exact: true }).click();
  await page.locator('.request-status').filter({ hasText: '판매 취소 거절됨' }).waitFor({ timeout: 25000 });
  await page.getByRole('button', { name: '판매 취소 요청', exact: true }).click();
  const refusedCancel = page.getByRole('alertdialog');
  await refusedCancel.getByText('판매 취소 요청이 거절된 상태예요').waitFor();
  assert.ok((await refusedCancel.innerText()).includes('배송·설치가 이미 진행되었어요'));
  await refusedCancel.getByLabel('문의 내용').fill('설치 전이라 다시 검토 부탁드려요.');
  await refusedCancel.getByRole('button', { name: '담당자에게 문의', exact: true }).click();
  await page.locator('.request-status').filter({ hasText: '재요청 문의 중' }).waitFor();
  await operator.getByRole('button', { name: '새로고침', exact: true }).click();
  await operator.getByRole('button', { name: '재요청 허용', exact: true }).click();
  await operator.getByRole('button', { name: '처리 확인', exact: true }).click();
  await page.locator('.request-status').filter({ hasNotText: '판매 취소 거절됨' }).waitFor({ timeout: 25000 });
  await page.getByRole('button', { name: '판매 취소 요청', exact: true }).click();
  await page.getByLabel('취소 요청 사유').fill('구매 일정 변경');
  await page.getByRole('button', { name: '취소 요청 전달', exact: true }).click();
  await page.locator('.request-status').filter({ hasText: '판매 취소 요청' }).waitFor();
  await operator.getByRole('button', { name: '새로고침', exact: true }).click();
  await operator.getByRole('button', { name: '판매 취소 승인', exact: true }).click();
  await panel.locator('select').selectOption('고객 요청을 받아 취소했어요');
  await panel.getByLabel('추가 설명 (선택)').fill('수신자 요청 확인');
  await operator.getByRole('button', { name: '처리 확인', exact: true }).click();
  await operator.getByRole('button', { name: '이력', exact: true }).click();
  await operator.locator('.request-history li').first().waitFor();
  assert.equal(await operator.locator('.request-history li').count(), 18);
  await operator.locator('.request-history').getByText('사유: 고객 요청을 받아 취소했어요 — 수신자 요청 확인', { exact: true }).waitFor();
  assert.ok((await operator.locator('.request-counts').innerText()).includes('전체 반응'));
  await operator.getByRole('button', { name: '담당 / 메모', exact: true }).click();
  const form = operator.locator('.request-panel form');
  await form.getByLabel('처리 상태').selectOption('CONTACTING');
  await form.getByLabel('담당자', { exact: true }).fill('김 담당');
  await form.getByLabel('내부 메모').fill('비공개: 다음 주 상담 예정');
  await form.getByRole('button', { name: '처리 확인', exact: true }).click();
  await operator.locator('tbody').getByText('담당자: 김 담당', { exact: true }).waitFor();
  await operator.getByText('저장한 메모: 비공개: 다음 주 상담 예정', { exact: true }).waitFor();
  assert.ok((await operator.locator('.request-panel').innerText()).includes('철회 1회'));
  const publicState = await call(recipient, `/api/links/s/${delivery.recipient.shortCode}/artwork-requests`);
  assert.equal(JSON.stringify(publicState).includes('비공개'), false);
  assert.ok(publicState[0].notices.some(notice => notice.reason === '고객 요청을 받아 취소했어요 — 수신자 요청 확인'));
  await operator.getByRole('button', { name: '이력 닫기', exact: true }).click();
  // URL-backed controlled inputs commit after React Router's navigation transition.
  await operator.getByLabel('구매의사 철회 이력 있음').click();
  await operator.waitForURL(url => url.searchParams.get('withdrawals') === 'true');
  await operator.locator('input[type="checkbox"]:checked').waitFor();
  assert.equal(await operator.getByLabel('구매의사 철회 이력 있음').isChecked(), true);
  await operator.locator('tbody tr').waitFor();
  assert.equal(await operator.locator('tbody tr').count(), 1);
  await operator.getByRole('button', { name: '이 작품 요청 모아보기', exact: true }).click();
  await operator.locator('tbody tr').waitFor();
  assert.ok(operator.url().includes(`artworkId=${artworkId}`));
  await operator.getByRole('button', { name: '이력', exact: true }).click();
  await operator.getByText('저장한 메모: 비공개: 다음 주 상담 예정', { exact: true }).waitFor();
  await screenshot(operator, 'admin-artwork-inbox');
  await operator.setViewportSize({ width: 390, height: 844 });
  assert.equal(await operator.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, 'Admin mobile page overflows');
  await screenshot(operator, 'admin-artwork-inbox-mobile');
  assert.deepEqual(errors, []);
  console.log('PASS: real passkey + email verification, interest, hold refusal popup + inquiry + retry, hold approval + extension, cancellation refusal popup + inquiry + retry, reasons shown to recipient, purchase/withdraw, sale/cancellation, durable timeline, summary, withdrawal filter, artwork grouping, private workflow notes/audit, mobile layout.');
} finally {
  if (browser) await browser.close();
  server.kill('SIGTERM');
}
