-- Durable rollback boundary: older matchers ignore additional JSON conditions.
ALTER TABLE rules ADD CONSTRAINT rules_trigger_conditions_shape CHECK (
    CASE
        WHEN NOT (trigger ? 'conditions') THEN true
        WHEN jsonb_typeof(trigger->'conditions') = 'array'
            THEN jsonb_array_length(trigger->'conditions') <= 3
        ELSE false
    END
);
