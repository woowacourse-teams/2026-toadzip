-- SH 수집이 주택임대 분류(건설임대)만 읽도록 바뀌었다. 이전 원천은 전체 분류를 받았고 분류를 저장하지 않아
-- 무엇이 건설임대인지 가려낼 수 없으므로 모두 지우고 다음 수집이 새 규칙으로 다시 채우게 한다.
-- SH 원천은 아직 공고를 만들지 않아 다른 표가 참조하지 않는다.
DELETE FROM sh_announcement_source;

-- 지운 원천의 열린 수집 실패는 더 받지 않을 수 있는 글이라 건너뜀으로 닫는다. 같은 요청이 다시 실패하면 다시 열린다.
UPDATE external_data_collection_failures
SET status = 'SKIPPED', resolved_at = now(), error_type = 'NOT_APPLICABLE',
    reason = 'SH 수집을 건설임대(주택임대 분류)만 읽도록 바꾸며 이전 원천과 함께 정리했습니다.'
WHERE source = 'SH_ANNOUNCEMENT' AND status = 'PENDING';
