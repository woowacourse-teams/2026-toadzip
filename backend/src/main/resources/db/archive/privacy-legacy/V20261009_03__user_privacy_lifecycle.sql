ALTER TABLE users ADD COLUMN registration_policy_version varchar(100);

CREATE TABLE user_deletion_markers (
    login_identifier_hash varchar(64) PRIMARY KEY,
    deleted_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT ck_user_deletion_marker_expiry CHECK (expires_at > deleted_at)
);

CREATE INDEX idx_user_deletion_markers_expiry ON user_deletion_markers(expires_at);
