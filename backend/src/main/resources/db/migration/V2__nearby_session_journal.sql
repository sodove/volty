CREATE TABLE nearby_actor_claims (
    actor_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    operation_id UUID,
    session_id UUID,
    room_epoch VARCHAR(128),
    node_epoch VARCHAR(128),
    CHECK ((operation_id IS NULL AND session_id IS NULL AND room_epoch IS NULL AND node_epoch IS NULL)
        OR (operation_id IS NOT NULL AND session_id IS NOT NULL AND room_epoch IS NOT NULL AND node_epoch IS NOT NULL))
);

CREATE TABLE nearby_session_operations (
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    destination_id UUID,
    session_id UUID UNIQUE,
    room_epoch VARCHAR(128),
    node_epoch VARCHAR(128),
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('PENDING','ACTIVE','CANCELLED','EXPIRED')),
    deadline_epoch_millis BIGINT,
    admission_pending BOOLEAN NOT NULL DEFAULT FALSE,
    cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (actor_id, operation_id),
    CHECK ((destination_id IS NULL AND session_id IS NULL AND room_epoch IS NULL AND node_epoch IS NULL)
        OR (destination_id IS NOT NULL AND session_id IS NOT NULL AND room_epoch IS NOT NULL AND node_epoch IS NOT NULL)),
    CHECK (phase NOT IN ('PENDING','ACTIVE') OR (session_id IS NOT NULL AND deadline_epoch_millis IS NOT NULL)),
    CHECK (NOT admission_pending OR phase = 'PENDING'),
    CHECK (NOT cleanup_pending OR (phase IN ('CANCELLED','EXPIRED') AND session_id IS NOT NULL))
);
CREATE INDEX nearby_session_expiry ON nearby_session_operations(deadline_epoch_millis) WHERE phase IN ('PENDING','ACTIVE');
CREATE INDEX nearby_session_work ON nearby_session_operations(actor_id) WHERE admission_pending OR cleanup_pending;
