-- Six-digit attendance codes are a second authenticated check-in method.
-- Expand the existing constraint without changing the applied V035/V051 migrations.
ALTER TABLE event_participants
    DROP CONSTRAINT ck_event_participant_method;

ALTER TABLE event_participants
    ADD CONSTRAINT ck_event_participant_method
    CHECK (check_method IS NULL OR check_method IN ('manual', 'qr', 'code'));
