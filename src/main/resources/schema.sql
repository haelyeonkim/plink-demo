CREATE TABLE IF NOT EXISTS protected_link (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    short_code    VARCHAR(32)   NOT NULL UNIQUE,
    owner_sub     VARCHAR(255),
    original_url  VARCHAR(2048) NOT NULL,
    title         VARCHAR(255),
    password_hash VARCHAR(255),
    expires_at    TIMESTAMP,
    recipient_names VARCHAR(500),
    max_views     INT DEFAULT 0,
    view_count    INT DEFAULT 0,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS link_view (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    link_id     BIGINT NOT NULL,
    viewer_name VARCHAR(100),
    viewed_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (link_id) REFERENCES protected_link(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS link_passkey (
    link_id BIGINT PRIMARY KEY,
    credential_id VARCHAR(1400) NOT NULL UNIQUE,
    user_handle VARCHAR(128) NOT NULL UNIQUE,
    public_key TEXT NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    receiver_name VARCHAR(100) NOT NULL,
    claimed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (link_id) REFERENCES protected_link(id) ON DELETE CASCADE
);
