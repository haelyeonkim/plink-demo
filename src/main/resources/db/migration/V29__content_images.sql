CREATE TABLE content_image (
    id VARCHAR(36) PRIMARY KEY,
    owner_sub VARCHAR(255) NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    storage_backend VARCHAR(20) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(50) NOT NULL,
    byte_size BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_content_image_owner ON content_image(owner_sub);
CREATE TABLE content_image_ref (
    content_id BIGINT NOT NULL REFERENCES link_content(id) ON DELETE CASCADE,
    image_id VARCHAR(36) NOT NULL REFERENCES content_image(id),
    PRIMARY KEY (content_id, image_id)
);
CREATE INDEX idx_content_image_ref_image ON content_image_ref(image_id);
