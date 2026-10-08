CREATE TABLE nearby_direct_call_push_outbox (
    event_id BIGSERIAL PRIMARY KEY,
    call_id UUID NOT NULL REFERENCES nearby_direct_calls(call_id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    call_revision BIGINT NOT NULL CHECK (call_revision >= 0),
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('RINGING','ACCEPTED','DECLINED','CANCELLED','MISSED','ENDED')),
    ring_deadline_millis BIGINT NOT NULL CHECK (ring_deadline_millis >= 0),
    UNIQUE (call_id,call_revision)
);
CREATE INDEX nearby_direct_call_push_actor ON nearby_direct_call_push_outbox(actor_id,event_id);
