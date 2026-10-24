-- 공급행의 주택형 표기를 원천 이름과 분리한다. 원천 이름은 정제의 매칭 기준으로만 쓴다.
ALTER TABLE supply_rows ADD COLUMN announcement_housing_type_name VARCHAR(255);

-- 지금 공개 화면은 관리자 보정·공통 모집 행에서 원천 이름을 보여 준다.
-- 같은 이름이 계속 보이도록, 연결 주택형 이름과 다른 경우만 공고 표시명으로 옮긴다.
UPDATE supply_rows r
SET announcement_housing_type_name = r.source_housing_type_name
FROM housing_types h
WHERE h.id = r.housing_type_id
  AND (r.admin_modified OR r.supply_group_id IS NOT NULL)
  AND r.source_housing_type_name <> h.name;
