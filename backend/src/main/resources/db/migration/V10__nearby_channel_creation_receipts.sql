CREATE TABLE nearby_channel_creations (
    channel_id UUID PRIMARY KEY REFERENCES nearby_voice_channels(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    community_id UUID NOT NULL REFERENCES nearby_communities(id) ON DELETE CASCADE,
    payload_hash VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0)
);
