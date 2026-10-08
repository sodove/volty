-- Existing journal history must not become new incoming-call notifications.
ALTER TABLE nearby_direct_call_push_outbox ADD COLUMN created_at_millis BIGINT CHECK (created_at_millis >= 0);
ALTER TABLE nearby_direct_call_push_outbox ALTER COLUMN created_at_millis SET DEFAULT ((extract(epoch from clock_timestamp())*1000)::bigint);
ALTER TABLE nearby_direct_call_push_outbox ADD COLUMN dispatch_cursor UUID;
ALTER TABLE nearby_direct_call_push_outbox ADD COLUMN materialized BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX nearby_push_unmaterialized ON nearby_direct_call_push_outbox(event_id) WHERE NOT materialized AND created_at_millis IS NOT NULL;

CREATE TABLE nearby_push_delivery_clock (
    singleton SMALLINT PRIMARY KEY CHECK (singleton=1),
    not_before_millis BIGINT NOT NULL CHECK (not_before_millis >= 0)
);
INSERT INTO nearby_push_delivery_clock VALUES (1,0);

CREATE TABLE nearby_push_deliveries (
    delivery_id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES nearby_direct_call_push_outbox(event_id) ON DELETE CASCADE,
    installation_id UUID NOT NULL REFERENCES nearby_push_devices(installation_id) ON DELETE CASCADE,
    expires_at_millis BIGINT NOT NULL CHECK (expires_at_millis >= 0),
    next_attempt_at_millis BIGINT NOT NULL CHECK (next_attempt_at_millis >= 0),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 6),
    done BOOLEAN NOT NULL DEFAULT FALSE,
    claim_ticket UUID,
    lease_until_millis BIGINT,
    claimed_generation BIGINT,
    claimed_binding_id UUID,
    claimed_token_hash CHAR(64),
    UNIQUE(event_id,installation_id),
    CHECK (lease_until_millis IS NULL OR (lease_until_millis >= 0 AND claim_ticket IS NOT NULL AND
           claimed_generation > 0 AND claimed_binding_id IS NOT NULL AND claimed_token_hash IS NOT NULL))
);
CREATE INDEX nearby_push_pending_delivery ON nearby_push_deliveries(delivery_id) WHERE NOT done;
