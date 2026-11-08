-- 공고 공급유형에 장기전세를 허용한다. GH 정제가 게시판 공급유형(장기전세)을 공고에 채우는데,
-- V20261101_01이 다시 만든 제약에 장기전세가 빠져 장기전세 GH 공고 정제가 실패했다. 단지 공급유형과 같은 값을 받는다.
ALTER TABLE announcements DROP CONSTRAINT announcements_supply_type_check;

ALTER TABLE announcements ADD CONSTRAINT announcements_supply_type_check CHECK (supply_type IN (
    'HAPPY_HOUSING',
    '행복주택',
    'NATIONAL_RENTAL',
    '국민임대',
    'PERMANENT_RENTAL',
    '영구임대',
    'PUBLIC_RENTAL',
    '공공임대',
    'PUBLIC_RENTAL_50Y',
    '50년공공임대',
    'INTEGRATED_PUBLIC_RENTAL',
    '통합공공임대',
    'REDEVELOPMENT_RENTAL',
    '재개발임대',
    'LONG_TERM_JEONSE',
    '장기전세',
    'ETC',
    '기타'
));
