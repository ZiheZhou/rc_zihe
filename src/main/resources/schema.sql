CREATE TABLE IF NOT EXISTS notification_request (
    id VARCHAR(36) PRIMARY KEY,
    vendor_key VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    payload TEXT,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255),
    locked_until TIMESTAMP NOT NULL,
    delivered_at TIMESTAMP,
    last_error VARCHAR(1024),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notification_request_status_retry_lock
    ON notification_request (status, next_retry_at, locked_until);

CREATE INDEX IF NOT EXISTS idx_notification_request_status_lock_until
    ON notification_request (status, locked_until);

CREATE INDEX IF NOT EXISTS idx_notification_request_vendor_status
    ON notification_request (vendor_key, status);

CREATE TABLE IF NOT EXISTS idempotency_record (
    id VARCHAR(36) PRIMARY KEY,
    vendor_key VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_idempotency_record_vendor_key UNIQUE (vendor_key, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_record_expires_at
    ON idempotency_record (expires_at);

CREATE TABLE IF NOT EXISTS vendor_config (
    vendor_key VARCHAR(255) PRIMARY KEY,
    endpoint VARCHAR(2048) NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    headers TEXT,
    body_template TEXT,
    timeout_ms INT NOT NULL,
    retry_policy TEXT,
    rate_limit TEXT,
    idempotency_key_location VARCHAR(16),
    idempotency_key_name VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_vendor_config_vendor_key
    ON vendor_config (vendor_key);
