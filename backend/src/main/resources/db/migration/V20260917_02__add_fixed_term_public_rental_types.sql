BEGIN;

ALTER TABLE announcements
    DROP CONSTRAINT IF EXISTS announcements_supply_type_check,
    ADD CONSTRAINT announcements_supply_type_check CHECK (supply_type IN (
        'HAPPY_HOUSING',
        '행복주택',
        'NATIONAL_RENTAL',
        '국민임대',
        'PERMANENT_RENTAL',
        '영구임대',
        'PUBLIC_RENTAL_5Y',
        '5년임대',
        'PUBLIC_RENTAL_10Y',
        '10년임대',
        'PUBLIC_RENTAL_50Y',
        '50년공공임대',
        'INTEGRATED_PUBLIC_RENTAL',
        '통합공공임대',
        'REDEVELOPMENT_RENTAL',
        '재개발임대',
        'ETC',
        '기타'
    ));

COMMIT;
