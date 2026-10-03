CREATE TABLE nearby_resource_deletions (
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    community_id UUID NOT NULL REFERENCES nearby_communities(id) ON DELETE CASCADE,
    channel_id UUID REFERENCES nearby_voice_channels(id),
    revision BIGINT NOT NULL CHECK (revision > 0),
    PRIMARY KEY (actor_id,operation_id)
);
CREATE TABLE nearby_resource_deletion_targets (
    actor_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    session_id UUID NOT NULL REFERENCES nearby_session_operations(session_id) ON DELETE CASCADE,
    PRIMARY KEY (actor_id,operation_id,session_id),
    FOREIGN KEY (actor_id,operation_id) REFERENCES nearby_resource_deletions(actor_id,operation_id) ON DELETE CASCADE
);
