-- 공공임대 5·10년을 분양전환 공공임대(PUBLIC_RENTAL) 하나로 합친다. LH 원천이 둘을 구분하지 않는다.
-- 이전 아티팩트는 PUBLIC_RENTAL을 읽지 못하므로 되돌릴 때 원래 값을 복원하도록 바꾼 행을 남긴다.
CREATE TABLE public_rental_term_backup (
    table_name varchar(30) NOT NULL,
    row_id bigint NOT NULL,
    supply_type varchar(30) NOT NULL,
    CONSTRAINT pk_public_rental_term_backup PRIMARY KEY (table_name, row_id)
);

INSERT INTO public_rental_term_backup (table_name, row_id, supply_type)
SELECT 'housing_complexes', id, supply_type FROM housing_complexes
WHERE supply_type IN ('PUBLIC_RENTAL_5Y', '5년임대', 'PUBLIC_RENTAL_10Y', '10년임대');

INSERT INTO public_rental_term_backup (table_name, row_id, supply_type)
SELECT 'announcements', id, supply_type FROM announcements
WHERE supply_type IN ('PUBLIC_RENTAL_5Y', '5년임대', 'PUBLIC_RENTAL_10Y', '10년임대');

UPDATE housing_complexes SET supply_type = 'PUBLIC_RENTAL'
WHERE supply_type IN ('PUBLIC_RENTAL_5Y', '5년임대', 'PUBLIC_RENTAL_10Y', '10년임대');

ALTER TABLE announcements DROP CONSTRAINT announcements_supply_type_check;

UPDATE announcements SET supply_type = 'PUBLIC_RENTAL'
WHERE supply_type IN ('PUBLIC_RENTAL_5Y', '5년임대', 'PUBLIC_RENTAL_10Y', '10년임대');

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
    'ETC',
    '기타'
));
