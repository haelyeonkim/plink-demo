-- A person is whoever holds the passkey, no longer an address they proved.
--
-- Claiming a link or a ticket asked for the email (or phone) it was sent to, and the
-- first passkey for an address waited for a code mailed there; the address named the
-- person. Now the first passkey registered on a link or ticket claims it, and somebody
-- who already has a Passlink passkey answers it to have the claim join their others.
-- A holder made this way has no email. Holders made before keep theirs, and their
-- user handles, so the passkeys already on people's phones go on working.
ALTER TABLE holder ALTER COLUMN email DROP NOT NULL;

-- An organiser could switch the contact check off for an event; there is no check left.
ALTER TABLE event_session DROP COLUMN claim_skip_check;
