-- How a holder shows a ticket link is theirs when registering it.
--
-- Registration used to offer a code mailed to the address, or the address typed back.
-- The code is gone: the holder now types the email or the phone number the ticket was
-- issued to, either one. An organiser who hands links out in person can switch the
-- check off, so holding the link is the whole claim; it is on unless they do.
ALTER TABLE event_session ADD COLUMN claim_skip_check BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE event_session DROP COLUMN claim_requires_otp;
