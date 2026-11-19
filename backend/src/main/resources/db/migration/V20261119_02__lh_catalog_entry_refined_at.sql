-- 묶음 수집이 LH 공고목록 행의 공급·상세를 받아 정제까지 마친 시각. 증분 수집은 이 시각 뒤 행이 바뀌지 않은 공고를 다시 받지 않는다.
ALTER TABLE lh_announcement_catalog_entries ADD COLUMN refined_at TIMESTAMPTZ;
