CREATE TABLE admin_accounts (
 id VARCHAR(36) PRIMARY KEY,
 username VARCHAR(50) NOT NULL,
 username_key VARCHAR(50) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 is_owner BOOLEAN NOT NULL,
 owner_slot SMALLINT UNIQUE,
 revision BIGINT NOT NULL DEFAULT 1,
 created_at VARCHAR(40) NOT NULL,
 updated_at VARCHAR(40) NOT NULL,
 CONSTRAINT admin_owner_slot CHECK ((is_owner = TRUE AND owner_slot = 1 AND owner_slot IS NOT NULL) OR (is_owner = FALSE AND owner_slot IS NULL))
);
