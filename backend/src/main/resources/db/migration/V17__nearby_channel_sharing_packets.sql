ALTER TABLE nearby_channel_sharing_consent ADD COLUMN started_at BIGINT CHECK (started_at >= 0);
-- Earlier receipts have no earned original start timestamp. Do not invent one on upgrade.
UPDATE nearby_channel_sharing_consent SET phase='REVOKED' WHERE phase='ACTIVE' AND started_at IS NULL;

CREATE TABLE nearby_channel_sharing_packets (
    actor_id UUID NOT NULL,
    session_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence >= 0),
    payload_hash VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    captured_at BIGINT NOT NULL CHECK (captured_at >= 0),
    deadline_epoch_millis BIGINT NOT NULL CHECK (deadline_epoch_millis > captured_at),
    PRIMARY KEY (actor_id,session_id,generation_id),
    FOREIGN KEY (actor_id,session_id,generation_id) REFERENCES nearby_channel_sharing_consent(actor_id,session_id,generation_id) ON DELETE CASCADE
);
