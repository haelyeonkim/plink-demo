-- Preserve artwork IDs when editing/reordering. Removed rows retain their identity
-- for previously issued links; negative positions are reserved for archived rows.
ALTER TABLE artwork ALTER COLUMN position SET DATA TYPE BIGINT;
ALTER TABLE artwork ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE content_artwork (
    content_id BIGINT NOT NULL REFERENCES link_content(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    artwork_id BIGINT REFERENCES artwork(id) ON DELETE SET NULL,
    PRIMARY KEY (content_id, position)
);
CREATE INDEX idx_content_artwork_identity ON content_artwork(artwork_id);

-- Original documents have an exact association. Old SELECTION snapshots do not;
-- administrators must explicitly associate those rather than matching by title.
INSERT INTO content_artwork (content_id, position, artwork_id)
SELECT source_content_id, CAST(position AS INTEGER), id FROM artwork;
