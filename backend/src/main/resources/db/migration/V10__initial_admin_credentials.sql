-- Existing accounts retain access. The application recognizes a still-active
-- admin/admin bootstrap account by checking its password hash at startup.
ALTER TABLE admin_accounts ADD COLUMN must_change_credentials BOOLEAN NOT NULL DEFAULT FALSE;
