CREATE TABLE admin_review_documents (
    resource VARCHAR(20) NOT NULL CHECK (resource IN ('COMPLEX', 'ANNOUNCEMENT')),
    resource_id BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    content BYTEA NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 26214400),
    actor VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (resource, resource_id, sha256)
);
CREATE INDEX idx_admin_review_documents_resource ON admin_review_documents(resource, resource_id, created_at DESC);
