-- 공고문 PDF로 새로 만드는 단지는 지번을 모르면 PNU 없이 만든다. NOT NULL 해제와 빈 문자열 금지만 하므로 이전 아티팩트와 함께 쓸 수 있다.
-- 공급행 소재지 PNU(supply_rows.supply_pnu)는 V20261101_04에서 이미 NULL을 허용했다.
ALTER TABLE housing_complexes ALTER COLUMN pnu DROP NOT NULL;
ALTER TABLE housing_complexes ADD CONSTRAINT ck_housing_complexes_pnu_not_blank
    CHECK (pnu IS NULL OR btrim(pnu) <> '');
