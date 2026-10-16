ALTER TABLE offline_events
    ADD COLUMN IF NOT EXISTS check_in_code VARCHAR(6);

WITH numbered AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY created_at, id) AS row_no
    FROM offline_events
    WHERE check_in_code IS NULL
)
UPDATE offline_events event
SET check_in_code = LPAD(((numbered.row_no + 100000) % 1000000)::TEXT, 6, '0')
FROM numbered
WHERE event.id = numbered.id;

ALTER TABLE offline_events
    ALTER COLUMN check_in_code SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_offline_events_check_in_code
    ON offline_events(check_in_code);
