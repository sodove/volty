ALTER TABLE nearby_media_webhook_inbox DROP CONSTRAINT nearby_media_webhook_inbox_kind_check;
ALTER TABLE nearby_media_webhook_inbox ALTER COLUMN session_id DROP NOT NULL;
ALTER TABLE nearby_media_webhook_inbox ALTER COLUMN participant_sid DROP NOT NULL;
ALTER TABLE nearby_media_webhook_inbox ADD CONSTRAINT nearby_media_webhook_kind CHECK (kind IN ('JOINED','LEFT','ROOM_FINISHED'));
ALTER TABLE nearby_media_webhook_inbox ADD CONSTRAINT nearby_media_webhook_participant_pair CHECK (
    (kind = 'ROOM_FINISHED' AND session_id IS NULL AND participant_sid IS NULL)
    OR (kind IN ('JOINED','LEFT') AND session_id IS NOT NULL AND participant_sid IS NOT NULL)
);
CREATE TABLE nearby_media_webhook_targets (
    event_sequence BIGINT NOT NULL REFERENCES nearby_media_webhook_inbox(sequence) ON DELETE CASCADE,
    session_id UUID NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (event_sequence, session_id)
);
INSERT INTO nearby_media_webhook_targets(event_sequence,session_id)
SELECT sequence,session_id FROM nearby_media_webhook_inbox WHERE NOT processed;
CREATE INDEX nearby_media_webhook_target_pending ON nearby_media_webhook_targets(event_sequence,session_id) WHERE NOT processed;
