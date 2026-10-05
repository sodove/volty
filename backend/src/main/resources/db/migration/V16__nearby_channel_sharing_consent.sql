-- V2 channel-session consent only. Legacy group sharing is deliberately untouched.
CREATE TABLE nearby_channel_sharing_consent (
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('ACTIVE','REVOKED','EXPIRED')),
    mode VARCHAR(16) CHECK (mode IN ('LOCATION','RIDE','FULL')),
    deadline_epoch_millis BIGINT,
    PRIMARY KEY (actor_id,session_id,generation_id),
    CHECK ((mode IS NULL) = (deadline_epoch_millis IS NULL)),
    CHECK (phase <> 'ACTIVE' OR mode IS NOT NULL),
    CHECK (deadline_epoch_millis IS NULL OR deadline_epoch_millis >= 0)
);
-- No session FK: a negative request must persist before its delayed positive request.
CREATE UNIQUE INDEX nearby_channel_sharing_one_live ON nearby_channel_sharing_consent(actor_id,session_id) WHERE phase='ACTIVE';
