CREATE TABLE nearby_direct_calls (
    call_id UUID PRIMARY KEY,
    caller_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    callee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key UUID NOT NULL,
    caller_operation_id UUID NOT NULL,
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('RINGING','ACCEPTED','DECLINED','CANCELLED','MISSED','ENDED')),
    ring_deadline_millis BIGINT NOT NULL CHECK (ring_deadline_millis >= 0),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    outcome VARCHAR(32),
    ever_rang BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (caller_id,idempotency_key),
    UNIQUE (caller_id,caller_operation_id),
    CHECK (caller_id <> callee_id),
    CHECK (ever_rang OR (phase='CANCELLED' AND outcome='CALLER_CANCELLED')),
    CHECK ((phase IN ('RINGING','ACCEPTED') AND outcome IS NULL)
        OR (outcome IS NOT NULL AND (
            (phase='DECLINED' AND outcome='CALLEE_DECLINED')
            OR (phase='CANCELLED' AND outcome IN ('CALLER_CANCELLED','CALLEE_CANCELLED','FRIENDSHIP_REVOKED'))
            OR (phase='MISSED' AND outcome='RING_TIMEOUT')
            OR (phase='ENDED' AND outcome IN ('CALLER_ENDED','CALLEE_ENDED','FRIENDSHIP_REVOKED')))))
);
CREATE INDEX nearby_direct_calls_ringing ON nearby_direct_calls(ring_deadline_millis) WHERE phase='RINGING';
CREATE INDEX nearby_direct_calls_callee ON nearby_direct_calls(callee_id,call_id);
ALTER TABLE nearby_actor_claims ADD COLUMN reserved_call_id UUID REFERENCES nearby_direct_calls(call_id) ON DELETE SET NULL;
ALTER TABLE nearby_actor_claims ADD CONSTRAINT nearby_call_reservation_exclusive
    CHECK (reserved_call_id IS NULL OR (operation_id IS NULL AND session_id IS NULL AND room_epoch IS NULL AND node_epoch IS NULL));
