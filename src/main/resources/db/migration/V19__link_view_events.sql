-- Two different things were being recorded as one: somebody opening the address, and
-- somebody proving with a passkey that the address is theirs. The first says the link
-- arrived; only the second says the document was read.
ALTER TABLE link_view ADD COLUMN event_type VARCHAR(32) NOT NULL DEFAULT 'PASSKEY_AUTHENTICATED';
CREATE INDEX idx_link_view_event ON link_view(link_id, event_type);
