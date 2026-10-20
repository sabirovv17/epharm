ALTER TABLE pharmacists
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);

COMMENT ON COLUMN pharmacists.password_hash IS
    'BCrypt hash for pharmacist IIN/password authentication; NULL until an administrator sets a password.';
