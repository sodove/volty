ALTER TABLE nearby_session_operations ADD COLUMN destination_kind VARCHAR(16) DEFAULT 'CHANNEL';
UPDATE nearby_session_operations SET destination_kind=NULL WHERE destination_id IS NULL;
ALTER TABLE nearby_session_operations ADD CONSTRAINT nearby_destination_kind
    CHECK ((destination_id IS NULL AND destination_kind IS NULL)
        OR (destination_id IS NOT NULL AND destination_kind IS NOT NULL AND destination_kind IN ('CHANNEL','DIRECT_CALL')));
ALTER TABLE nearby_roster_outbox ADD COLUMN destination_kind VARCHAR(16) NOT NULL DEFAULT 'CHANNEL'
    CHECK (destination_kind IN ('CHANNEL','DIRECT_CALL'));
CREATE INDEX nearby_session_typed_destination ON nearby_session_operations(destination_kind,destination_id);
