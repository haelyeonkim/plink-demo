/**
 * How a link or a ticket is claimed, as both apps show it. There is nothing to type: the
 * first passkey registered on it claims it. Somebody who already has a Passlink passkey
 * answers it instead of making another, so the page offers one way first.
 */
export type ClaimChoice = 'new' | 'existing';

/** Marks this browser as one where a Passlink passkey was made; nothing about the person. */
const MADE = 'passlink.passkey.made';

export function rememberPasskey() {
  try { localStorage.setItem(MADE, '1'); } catch { /* private mode: the server's hint still stands */ }
}

/**
 * Which way to offer first: the server's guess from the email or phone the claim was sent
 * to, or this browser having made a passkey before. Either says "existing".
 */
export function preferredChoice(suggest?: ClaimChoice | null): ClaimChoice {
  if (suggest === 'existing') return 'existing';
  try { if (localStorage.getItem(MADE) === '1') return 'existing'; } catch { /* unreadable: say new */ }
  return 'new';
}

/**
 * The phone refused to make a passkey because it already holds one of this person's
 * (the registration excluded them). The claim goes on by answering that one instead.
 */
export function alreadyOnDevice(error: unknown): boolean {
  const cause = error instanceof Error && 'reason' in error ? (error as { reason: unknown }).reason : error;
  return cause instanceof DOMException && cause.name === 'InvalidStateError';
}
