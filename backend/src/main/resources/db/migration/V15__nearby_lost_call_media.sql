ALTER TABLE nearby_direct_calls DROP CONSTRAINT nearby_direct_call_outcome;
ALTER TABLE nearby_direct_calls ADD CONSTRAINT nearby_direct_call_outcome
    CHECK ((phase IN ('RINGING','ACCEPTED') AND outcome IS NULL)
        OR (outcome IS NOT NULL AND (
            (phase='DECLINED' AND outcome='CALLEE_DECLINED')
            OR (phase='CANCELLED' AND outcome IN ('CALLER_CANCELLED','CALLEE_CANCELLED','FRIENDSHIP_REVOKED'))
            OR (phase='MISSED' AND outcome='RING_TIMEOUT')
            OR (phase='ENDED' AND outcome IN ('CALLER_ENDED','CALLEE_ENDED','FRIENDSHIP_REVOKED','MEDIA_TIMEOUT','MEDIA_LOST')))));
