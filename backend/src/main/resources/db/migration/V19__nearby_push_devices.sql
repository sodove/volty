CREATE TABLE nearby_push_devices (
    installation_id UUID PRIMARY KEY,
    installation_key_hash CHAR(64) NOT NULL CHECK (installation_key_hash ~ '^[0-9a-f]{64}$'),
    generation BIGINT NOT NULL CHECK (generation > 0),
    binding_id UUID NOT NULL,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    auth_issued_at_seconds BIGINT NOT NULL CHECK (auth_issued_at_seconds >= 0),
    token TEXT,
    token_hash CHAR(64),
    updated_at_millis BIGINT NOT NULL CHECK (updated_at_millis >= 0),
    CHECK ((token IS NULL AND token_hash IS NULL) OR
           (token IS NOT NULL AND length(token) BETWEEN 1 AND 4096 AND token_hash IS NOT NULL AND token_hash ~ '^[0-9a-f]{64}$'))
);
CREATE UNIQUE INDEX nearby_push_devices_token ON nearby_push_devices(token_hash) WHERE token_hash IS NOT NULL;
CREATE INDEX nearby_push_devices_actor ON nearby_push_devices(actor_id,installation_id) WHERE token IS NOT NULL;
