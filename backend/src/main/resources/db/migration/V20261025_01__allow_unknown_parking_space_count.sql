-- 입주 전 신규 단지는 공고문·마이홈 어디에도 주차대수가 없을 수 있어 관리자가 비워 둘 수 있게 한다.
ALTER TABLE housing_complexes ALTER COLUMN parking_space_count DROP NOT NULL;
