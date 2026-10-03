CREATE TABLE nearby_access_fence (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1)
);
INSERT INTO nearby_access_fence(singleton) VALUES (1);

CREATE TABLE nearby_communities (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES users(id),
    create_request_hash VARCHAR(64),
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','PUBLIC')),
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','DELETED')),
    created_at BIGINT NOT NULL
);

CREATE TABLE nearby_community_members (
    community_id UUID NOT NULL REFERENCES nearby_communities(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at BIGINT NOT NULL,
    PRIMARY KEY (community_id,user_id)
);
ALTER TABLE nearby_communities ADD CONSTRAINT nearby_community_owner_member
    FOREIGN KEY (id,owner_id) REFERENCES nearby_community_members(community_id,user_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX nearby_members_user ON nearby_community_members(user_id,community_id);

CREATE TABLE nearby_voice_channels (
    id UUID PRIMARY KEY,
    community_id UUID NOT NULL REFERENCES nearby_communities(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','DELETED')),
    created_at BIGINT NOT NULL
);
CREATE INDEX nearby_channels_community ON nearby_voice_channels(community_id,id);
CREATE UNIQUE INDEX nearby_channels_one_default ON nearby_voice_channels(community_id) WHERE is_default;

CREATE TABLE nearby_community_bans (
    community_id UUID NOT NULL REFERENCES nearby_communities(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (community_id,user_id)
);

CREATE TABLE nearby_community_outbox (
    revision BIGINT PRIMARY KEY CHECK (revision > 0),
    community_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK (kind IN (
        'CREATED','UPDATED','MEMBER_JOINED','MEMBER_LEFT','OWNER_TRANSFERRED',
        'MEMBER_REMOVED','MEMBER_BANNED','MEMBER_UNBANNED','CHANNEL_CREATED',
        'CHANNEL_DELETED','DELETED','INVITE_CHANGED'
    )),
    channel_id UUID,
    subject_id UUID
);
CREATE INDEX nearby_community_outbox_resource ON nearby_community_outbox(community_id,revision);

INSERT INTO nearby_communities(id,name,owner_id,created_by,visibility,created_at)
    SELECT id,name,owner_id,owner_id,'PRIVATE',created_at FROM groups;
INSERT INTO nearby_community_members(community_id,user_id,joined_at)
    SELECT group_id,user_id,joined_at FROM group_members;
INSERT INTO nearby_community_members(community_id,user_id,joined_at)
    SELECT id,owner_id,created_at FROM groups ON CONFLICT DO NOTHING;
INSERT INTO nearby_voice_channels(id,community_id,name,is_default,created_at)
    SELECT md5('volty-default-channel:' || id::text)::uuid,id,'Общий',TRUE,created_at FROM groups;
