CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    deleted_at BIGINT,
    tokens_revoked_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS one_time_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at BIGINT NOT NULL,
    consumed_at BIGINT
);
CREATE INDEX IF NOT EXISTS one_time_tokens_lookup ON one_time_tokens(purpose, token_hash, expires_at);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    revoked_at BIGINT,
    replaced_by_hash VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS refresh_tokens_user ON refresh_tokens(user_id);

CREATE TABLE IF NOT EXISTS friendships (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    addressee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    CHECK (requester_id <> addressee_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS friendships_pair ON friendships(LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id));

CREATE TABLE IF NOT EXISTS groups (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invite_code VARCHAR(32) NOT NULL UNIQUE,
    invite_expires_at BIGINT,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS group_members (
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    joined_at BIGINT NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE TABLE IF NOT EXISTS sharing_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    profile VARCHAR(16) NOT NULL,
    started_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT
);
CREATE INDEX IF NOT EXISTS sharing_active ON sharing_sessions(group_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS live_updates (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    location_json TEXT,
    telemetry_json TEXT,
    captured_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, group_id)
);
