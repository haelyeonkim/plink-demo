-- A photo on a board notice, and an organiser notice raised on every holder's ticket.

-- One photo uploaded for the event's board, by the organiser or a booth. The bytes live
-- in the same private store as content images; the row says where. A photo not attached
-- to a notice within a day is removed.
CREATE TABLE ad_image (
    id              VARCHAR(36)  PRIMARY KEY,
    session_id      BIGINT       NOT NULL REFERENCES event_session(id) ON DELETE CASCADE,
    storage_key     VARCHAR(255) NOT NULL,
    storage_backend VARCHAR(20)  NOT NULL,
    media_type      VARCHAR(50)  NOT NULL,
    byte_size       BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ad_image_session ON ad_image(session_id);

ALTER TABLE ad_post ADD COLUMN image_id VARCHAR(36) REFERENCES ad_image(id) ON DELETE SET NULL;

-- Set while an organiser notice is also shown on the holder's ticket, entered or not.
ALTER TABLE chat_message ADD COLUMN alert_until TIMESTAMP;
