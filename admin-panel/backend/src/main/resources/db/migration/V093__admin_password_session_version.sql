ALTER TABLE admin_users ADD COLUMN session_version bigint NOT NULL DEFAULT 0
    CHECK (session_version >= 0);

-- Metadata only: never store supplied passwords, hashes or bearer/refresh tokens here.
CREATE TABLE admin_password_change_audit (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES admin_users(id),
    session_version bigint NOT NULL CHECK (session_version > 0),
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE admin_password_attempt_limits (
    account_id uuid PRIMARY KEY REFERENCES admin_users(id) ON DELETE CASCADE,
    window_started_at timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 6)
);
