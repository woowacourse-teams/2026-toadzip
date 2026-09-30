-- Preserve existing users; reject ambiguous existing identifiers instead of merging accounts.
ALTER TABLE users ADD CONSTRAINT uk_users_login_identifier UNIQUE (login_identifier);
