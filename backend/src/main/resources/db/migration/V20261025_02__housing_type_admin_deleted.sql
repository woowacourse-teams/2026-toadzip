-- 관리자가 휴지통으로 옮긴 주택형은 기록을 보존하고 공개·후보 조회에서 제외한다.
ALTER TABLE housing_types ADD COLUMN admin_deleted boolean NOT NULL DEFAULT false;
