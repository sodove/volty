CREATE TABLE nearby_media_webhook_inbox (
    sequence BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(256) NOT NULL UNIQUE,
    body_sha256 CHAR(64) NOT NULL CHECK (body_sha256 ~ '^[a-f0-9]{64}$'),
    room_epoch VARCHAR(128) NOT NULL,
    session_id UUID NOT NULL,
    participant_sid VARCHAR(256) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('JOINED','LEFT')),
    processed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX nearby_media_webhook_pending ON nearby_media_webhook_inbox(sequence) WHERE NOT processed;
