-- 주택형 기본 임대조건의 출처. 마이홈 단지 정제가 채운 값은 MYHOME, PDF 보정이 공고 값으로 바꾼 값은 ANNOUNCEMENT다.
-- 사용자 화면이 출처와 그 날짜(수집일 또는 공고일)를 보여 준다.
ALTER TABLE housing_types ADD COLUMN rental_condition_source VARCHAR(20);
UPDATE housing_types SET rental_condition_source = 'MYHOME' WHERE rental_condition_collected_at IS NOT NULL;
ALTER TABLE housing_types ADD CONSTRAINT ck_housing_types_rental_condition_source
    CHECK (rental_condition_source IN ('MYHOME', 'ANNOUNCEMENT'));
