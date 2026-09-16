-- Per-account access to the two consoles.
--
-- Existing accounts keep everything they had, and the first account becomes the owner:
-- somebody has to be able to hand out permissions, and locking the only administrator
-- out of that screen during a migration would need a database session to undo.
ALTER TABLE admin_account ADD COLUMN can_links   BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE admin_account ADD COLUMN can_tickets BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE admin_account ADD COLUMN is_owner    BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE admin_account SET is_owner = TRUE
 WHERE id = (SELECT MIN(id) FROM admin_account);
