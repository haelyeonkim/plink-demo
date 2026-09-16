-- Private links now use the same passkey identity as tickets: one credential per person,
-- keyed by the address the recipient was issued to, instead of one credential per link.
--
-- A recipient is issued to an email the way a ticket is, and registering attaches that
-- person's existing passkey rather than minting another entry on their phone.
ALTER TABLE link_recipient ADD COLUMN email     VARCHAR(255);
ALTER TABLE link_recipient ADD COLUMN holder_id BIGINT REFERENCES holder(id);
CREATE INDEX idx_link_recipient_holder ON link_recipient(holder_id);

-- A memo that already carries an address becomes the issued address.
UPDATE link_recipient SET email = label WHERE label LIKE '%@%';

-- Credentials that belonged to an address rather than to a person cannot be attributed
-- to one, so those addresses go back to unclaimed and are registered again.
UPDATE link_recipient SET status = 'ISSUED', claimed_at = NULL
 WHERE id IN (SELECT recipient_id FROM recipient_passkey);

DROP TABLE recipient_passkey;
