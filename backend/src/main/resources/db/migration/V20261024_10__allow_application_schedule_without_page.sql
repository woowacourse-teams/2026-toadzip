-- 확정 접수 일정의 근거는 공고문 URL로 충분하고, 공고문 페이지는 알 때만 남긴다.
ALTER TABLE announcement_application_schedules ALTER COLUMN source_page DROP NOT NULL;
