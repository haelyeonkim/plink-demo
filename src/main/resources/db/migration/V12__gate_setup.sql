-- Terminals are enrolled the same way ticket holders are: a link plus a code.
--
-- Typing a long token into a tablet is error-prone and the token tends to end up in a
-- group chat. A setup link carries the identity and a short code proves the person
-- holding the tablet was told it through another channel - the same two-channel
-- argument as the ticket claim.
ALTER TABLE gate ADD COLUMN setup_token_hmac   VARCHAR(64);
ALTER TABLE gate ADD COLUMN setup_token_cipher TEXT;
ALTER TABLE gate ADD COLUMN setup_code_hash    VARCHAR(64);
ALTER TABLE gate ADD COLUMN setup_code_cipher  TEXT;
ALTER TABLE gate ADD COLUMN setup_expires_at   TIMESTAMP;
ALTER TABLE gate ADD COLUMN setup_attempts     INT NOT NULL DEFAULT 0;
CREATE INDEX idx_gate_setup ON gate(setup_token_hmac);
