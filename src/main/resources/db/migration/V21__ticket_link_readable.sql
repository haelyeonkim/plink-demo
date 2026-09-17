-- The issued link, kept so the console can show it again.
--
-- The token was stored only as an HMAC, which meant an operator who was asked "what was
-- my link?" could do nothing but mint a new one and break the old. The HMAC stays - it
-- is what a request is looked up by - and the readable copy sits beside it, sealed with
-- the same AES-GCM the gate terminal tokens already use, so the database alone does not
-- hand anyone a ticket.
ALTER TABLE ticket ADD COLUMN token_cipher TEXT;

-- The recipient's claim token, so a transferred ticket's link stays readable too.
ALTER TABLE ticket_transfer ADD COLUMN to_token_cipher TEXT;
