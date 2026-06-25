CREATE TABLE IF NOT EXISTS notifications (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- Core
    type            VARCHAR(10)  NOT NULL,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(255) NULL,
    content         TEXT         NOT NULL,
    status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    -- Sender identity
    from_address    VARCHAR(255) NULL,
    reply_to        VARCHAR(255) NULL,
    sender_id       VARCHAR(50)  NULL,
    -- Extended (email only, stored as JSON arrays)
    cc              TEXT         NULL,
    bcc             TEXT         NULL,
    content_type    VARCHAR(20)  NOT NULL DEFAULT 'text/plain',
    attachments     TEXT         NULL,
    -- Retry
    retry_count     TINYINT      NOT NULL DEFAULT 0,
    last_error      VARCHAR(500) NULL,
    -- Scheduling
    scheduled_at    DATETIME     NULL,
    -- Tracking timestamps
    sent_at         DATETIME     NULL,
    delivered_at    DATETIME     NULL,
    read_at         DATETIME     NULL,
    -- Idempotency
    idempotency_key VARCHAR(64)  NULL,
    -- Soft delete
    deleted_at      DATETIME     NULL,
    -- Standard timestamps
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_idempotency_key (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_notifications_created_at ON notifications (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_scheduled  ON notifications (scheduled_at, status, deleted_at);
CREATE INDEX IF NOT EXISTS idx_notifications_status     ON notifications (status, deleted_at);
