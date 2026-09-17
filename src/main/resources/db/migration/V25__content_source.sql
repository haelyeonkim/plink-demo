-- Provenance for authored/imported content. URL sources are stored as SHA-256 rather
-- than raw addresses because query strings may contain access tokens.
ALTER TABLE link_content ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE link_content ADD COLUMN source_ref VARCHAR(500);
ALTER TABLE link_content ADD COLUMN source_imported_at TIMESTAMP;

ALTER TABLE link_content ADD CONSTRAINT chk_link_content_source_type
    CHECK (source_type IN ('MANUAL', 'URL', 'PDF', 'SELECTION'));
