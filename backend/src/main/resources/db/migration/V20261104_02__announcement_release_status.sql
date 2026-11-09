-- 원천에서 일정·유형을 다 읽지 못한 공고는 미확정으로 두고 관리자 보정 뒤 공개한다. 기존 공고는 모두 공개다.
ALTER TABLE announcements ADD COLUMN release_status varchar(20) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE announcements ADD CONSTRAINT ck_announcements_release_status
    CHECK (release_status IN ('UNCONFIRMED', 'PUBLISHED'));

-- 필수값 NOT NULL은 공개 공고에만 건다. 미확정 공고만 접수·발표 일정과 공급·모집유형을 비울 수 있다.
ALTER TABLE announcements ALTER COLUMN application_start_date DROP NOT NULL;
ALTER TABLE announcements ALTER COLUMN application_end_date DROP NOT NULL;
ALTER TABLE announcements ALTER COLUMN winner_announcement_date DROP NOT NULL;
ALTER TABLE announcements ALTER COLUMN supply_type DROP NOT NULL;
ALTER TABLE announcements ALTER COLUMN recruitment_type DROP NOT NULL;
ALTER TABLE announcements ADD CONSTRAINT ck_announcements_published_required
    CHECK (release_status = 'UNCONFIRMED' OR (application_start_date IS NOT NULL
        AND application_end_date IS NOT NULL AND winner_announcement_date IS NOT NULL
        AND supply_type IS NOT NULL AND recruitment_type IS NOT NULL));
