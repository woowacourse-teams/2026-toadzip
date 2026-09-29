ALTER TABLE announcement_schedules ADD COLUMN complex_name varchar(255);

-- 기존 일정은 LH 재정제 시 검증된 조회 조건의 원천에서 단지명을 채운다.
-- 원천 행 순서만으로 과거 일정과 현재 원천을 연결해 역채움하지 않는다.
