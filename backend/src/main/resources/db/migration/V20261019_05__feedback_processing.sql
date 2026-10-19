ALTER TABLE feedback
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN status varchar(30) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'ON_HOLD', 'DONE')),
    ADD COLUMN assignee varchar(255) NOT NULL DEFAULT '',
    ADD COLUMN note text NOT NULL DEFAULT '',
    ADD COLUMN related_resource varchar(30) NOT NULL DEFAULT '',
    ADD COLUMN related_id bigint,
    ADD COLUMN actor varchar(255),
    ADD COLUMN processed_at timestamptz,
    ADD CONSTRAINT ck_feedback_related CHECK ((related_resource = '' AND related_id IS NULL)
        OR (related_resource IN ('COMPLEX', 'ANNOUNCEMENT') AND related_id IS NOT NULL AND related_id > 0));
CREATE INDEX idx_feedback_status_created ON feedback(status, created_at DESC, id DESC);
