CREATE TABLE admin_quality_tasks (
    resource VARCHAR(20) NOT NULL CHECK (resource IN ('COMPLEX', 'ANNOUNCEMENT')),
    resource_id BIGINT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    assignee VARCHAR(255) NOT NULL,
    due_date DATE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'ON_HOLD', 'DONE')),
    note VARCHAR(2000) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (resource, resource_id)
);
CREATE INDEX idx_admin_quality_tasks_assignee_due ON admin_quality_tasks(assignee, due_date, status);
