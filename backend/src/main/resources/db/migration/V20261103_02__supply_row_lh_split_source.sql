-- LH 공급 원본 한 행(통합행)을 관리자가 공고문 PDF를 근거로 주택형별 공급행 여러 개로 나눌 때, 나뉜 공급행마다 그 LH 행 식별자
-- "LH:{panId}:SUPPLY:{순번}"을 남긴다. 통합행은 ID와 lh_source_supply_row_identifier를 유지한 채 첫 분리행이 되고 나머지는 새 행이다.
-- 같은 LH 행을 가리키는 공급행이 여럿이어도 의도한 분리인지 중복인지 가리고, 그 LH 행을 이미 공급행이 된 행으로 보기 위해서다.
-- 분리하지 않은 공급행은 NULL이므로 NULL 허용 열 추가만 하며 이전 아티팩트와 함께 쓸 수 있다.
ALTER TABLE supply_rows ADD COLUMN lh_split_source_identifier VARCHAR(255);
ALTER TABLE supply_rows ADD CONSTRAINT ck_supply_rows_lh_split_source_not_blank
    CHECK (lh_split_source_identifier IS NULL OR btrim(lh_split_source_identifier) <> '');
