CREATE TABLE nearby_roster_clock (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    revision BIGINT NOT NULL CHECK (revision >= 0)
);
INSERT INTO nearby_roster_clock(singleton, revision) VALUES (1, 0);

CREATE TABLE nearby_roster_outbox (
    revision BIGINT PRIMARY KEY CHECK (revision > 0),
    actor_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    destination_id UUID NOT NULL,
    session_id UUID NOT NULL,
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('PENDING','ACTIVE','CANCELLED','EXPIRED')),
    deadline_epoch_millis BIGINT NOT NULL
);
CREATE INDEX nearby_roster_outbox_destination ON nearby_roster_outbox(destination_id, revision);
