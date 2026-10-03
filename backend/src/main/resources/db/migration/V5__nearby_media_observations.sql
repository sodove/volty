ALTER TABLE nearby_session_operations ADD COLUMN participant_sid VARCHAR(256);
ALTER TABLE nearby_session_operations ADD COLUMN media_state VARCHAR(16);
ALTER TABLE nearby_session_operations ADD COLUMN media_observation_version BIGINT NOT NULL DEFAULT 0 CHECK (media_observation_version >= 0);
ALTER TABLE nearby_session_operations ADD CONSTRAINT nearby_media_pair CHECK (
    (participant_sid IS NULL AND media_state IS NULL)
    OR (participant_sid IS NOT NULL AND media_state IS NOT NULL AND media_state IN ('JOINING','JOINED','ACTIVE','DISCONNECTED'))
);
ALTER TABLE nearby_roster_outbox ADD COLUMN participant_sid VARCHAR(256);
ALTER TABLE nearby_roster_outbox ADD COLUMN media_state VARCHAR(16);
