-- 정제가 아니라 관리자가 공고를 보정해 실패를 해결로 바꾼 경우 그 관리자를 남긴다. 정제가 해결한 실패는 NULL이다.
-- 기존 해결 기록은 누가 해결했는지 알 수 없어 채우지 않는다. 이전 버전 애플리케이션은 이 열을 읽지 않는다.
ALTER TABLE myhome_announcement_mapping_failures ADD COLUMN last_resolved_by varchar(255);
ALTER TABLE myhome_complex_mapping_failures ADD COLUMN last_resolved_by varchar(255);
ALTER TABLE lh_announcement_enrichment_failures ADD COLUMN last_resolved_by varchar(255);
ALTER TABLE lh_household_enrichment_failures ADD COLUMN last_resolved_by varchar(255);
