CREATE TABLE admin_review_drafts (
    resource VARCHAR(20) NOT NULL CHECK (resource IN ('COMPLEX', 'ANNOUNCEMENT')),
    resource_id BIGINT NOT NULL,
    target VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    document_version BIGINT NOT NULL CHECK (document_version >= 0),
    sha256 VARCHAR(64) NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    pages VARCHAR(500) NOT NULL,
    result_json TEXT NOT NULL CHECK (octet_length(result_json) <= 1000000),
    note VARCHAR(2000) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (resource, resource_id, target),
    FOREIGN KEY (resource, resource_id, sha256) REFERENCES admin_review_documents(resource, resource_id, sha256)
);
