CREATE TABLE nearby_invitations (
    id UUID PRIMARY KEY,
    community_id UUID NOT NULL REFERENCES nearby_communities(id),
    created_by UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) UNIQUE NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL CHECK (created_at >= 0),
    expires_at BIGINT NOT NULL CHECK (expires_at > created_at),
    max_uses INTEGER NOT NULL CHECK (max_uses BETWEEN 1 AND 1000),
    uses INTEGER NOT NULL DEFAULT 0 CHECK (uses BETWEEN 0 AND max_uses),
    revoked_at BIGINT,
    creation_revision BIGINT NOT NULL CHECK (creation_revision > 0),
    revocation_revision BIGINT,
    CHECK ((revoked_at IS NULL AND revocation_revision IS NULL) OR
           (revoked_at IS NOT NULL AND revoked_at >= 0 AND revocation_revision IS NOT NULL AND revocation_revision > 0))
);
CREATE INDEX nearby_invitations_community ON nearby_invitations(community_id,id);
CREATE TABLE nearby_invitation_acceptances (
    actor_id UUID NOT NULL REFERENCES users(id),
    operation_id UUID NOT NULL,
    invitation_id UUID NOT NULL REFERENCES nearby_invitations(id),
    token_hash VARCHAR(64) NOT NULL,
    community_id UUID NOT NULL REFERENCES nearby_communities(id),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    membership_changed BOOLEAN NOT NULL,
    PRIMARY KEY (actor_id,operation_id)
);
