-- Existing users remain at version zero. Password changes revoke older sessions on their next request.
ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
