CREATE TABLE street_view_policies (
    id BIGINT PRIMARY KEY CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    change_reason VARCHAR(500) NOT NULL CHECK (btrim(change_reason) <> ''),
    updated_by VARCHAR(255) NOT NULL CHECK (btrim(updated_by) <> ''),
    updated_at TIMESTAMPTZ NOT NULL
);

INSERT INTO street_view_policies (id, enabled, version, change_reason, updated_by, updated_at)
VALUES (1, FALSE, 0, 'FE 연동 검증 전 기본 비활성화', 'SYSTEM_MIGRATION', CURRENT_TIMESTAMP);
