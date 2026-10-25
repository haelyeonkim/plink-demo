-- One-time reconciliation of the artwork stage's historical version collision.
-- Run only after backing up both schemas and deploying migrations V32-V34.
-- Follow with Flyway migrate(outOfOrder=true) once, then normal validate.
-- No business data or checksums are changed here.
BEGIN;
LOCK TABLE stage_artwork_20261007.flyway_schema_history IN EXCLUSIVE MODE;
DO $$
BEGIN
    IF (SELECT count(*) FROM stage_artwork_20261007.flyway_schema_history
        WHERE version = '32' AND script = 'V32__artwork_sale_status.sql'
          AND checksum = -1494080826 AND success) <> 1
       OR (SELECT count(*) FROM stage_artwork_20261007.flyway_schema_history
        WHERE version = '33' AND script = 'V33__shared_artwork_status.sql'
          AND checksum = -1788089993 AND success) <> 1
       OR EXISTS (SELECT 1 FROM stage_artwork_20261007.flyway_schema_history
                  WHERE version::numeric > 33) THEN
        RAISE EXCEPTION 'Unexpected stage migration history; no changes made';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='stage_artwork_20261007'
                     AND table_name='artwork' AND column_name='sale_status')
       OR NOT EXISTS (SELECT 1 FROM information_schema.tables
                      WHERE table_schema='stage_artwork_20261007'
                        AND table_name='content_artwork') THEN
        RAISE EXCEPTION 'Stage artwork migrations are not present';
    END IF;
END $$;
UPDATE stage_artwork_20261007.flyway_schema_history
   SET version='34', script='V34__shared_artwork_status.sql'
 WHERE version='33';
UPDATE stage_artwork_20261007.flyway_schema_history
   SET version='33', script='V33__artwork_sale_status.sql'
 WHERE version='32';
COMMIT;
