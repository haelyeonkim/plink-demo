-- Public link addresses are namespaced by the account that issued them:
--   /s/{slug}/{code}
-- The code alone still identifies the recipient; the slug says who sent it, which is
-- the first thing somebody asks when an unexpected link arrives.
ALTER TABLE admin_account ADD COLUMN slug VARCHAR(40);

-- Seed from the address's local part, keeping only what belongs in a path segment.
UPDATE admin_account
   SET slug = LOWER(REGEXP_REPLACE(SUBSTRING(email FROM 1 FOR POSITION('@' IN email) - 1),
                                   '[^A-Za-z0-9-]', '-', 'g'));

-- A local part that leaves nothing usable falls back to the account number.
UPDATE admin_account SET slug = 'u' || id
 WHERE slug IS NULL OR REPLACE(slug, '-', '') = '';

-- Two accounts can share a local part across domains; the later one carries its id.
UPDATE admin_account SET slug = slug || '-' || id
 WHERE id NOT IN (SELECT MIN(id) FROM admin_account GROUP BY slug);

ALTER TABLE admin_account ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX idx_admin_account_slug ON admin_account(slug);
