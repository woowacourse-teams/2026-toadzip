-- 마이홈 정제가 원천 분할 단지를 합칠 때는 통합 이력 없이 기존 단지 ID를 별칭으로 남긴다.
ALTER TABLE housing_complex_aliases ALTER COLUMN merge_id DROP NOT NULL;
