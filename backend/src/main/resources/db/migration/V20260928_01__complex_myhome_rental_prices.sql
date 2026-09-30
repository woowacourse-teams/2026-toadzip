ALTER TABLE housing_complexes
    ADD COLUMN deposit_min BIGINT,
    ADD COLUMN deposit_max BIGINT,
    ADD COLUMN monthly_rent_min BIGINT,
    ADD COLUMN monthly_rent_max BIGINT;

ALTER TABLE housing_complexes
    ADD CONSTRAINT ck_complex_deposit_range CHECK (
        (deposit_min IS NULL AND deposit_max IS NULL)
        OR (deposit_min >= 0 AND deposit_max >= deposit_min)
    ),
    ADD CONSTRAINT ck_complex_monthly_rent_range CHECK (
        (monthly_rent_min IS NULL AND monthly_rent_max IS NULL)
        OR (monthly_rent_min >= 0 AND monthly_rent_max >= monthly_rent_min)
    );

-- 기존에 연결된 모든 마이홈 원천을 사용한다. 통합된 단지는 여러 링크의 가격 범위를 합친다.
WITH source_prices AS (
    SELECT link.housing_complex_id,
           MIN(source.bass_rent_gtn) FILTER (WHERE source.bass_rent_gtn >= 0) AS deposit_min,
           MAX(source.bass_rent_gtn) FILTER (WHERE source.bass_rent_gtn >= 0) AS deposit_max,
           MIN(source.bass_mt_rntchrg) FILTER (WHERE source.bass_mt_rntchrg >= 0) AS monthly_rent_min,
           MAX(source.bass_mt_rntchrg) FILTER (WHERE source.bass_mt_rntchrg >= 0) AS monthly_rent_max
    FROM myhome_complex_links link
    JOIN myhome_complex_source source ON link.source_complex_identifier =
        source.hsmp_sn::text || ':' || CASE BTRIM(source.suply_ty_nm)
            WHEN '행복주택' THEN 'HAPPY_HOUSING'
            WHEN '국민임대' THEN 'NATIONAL_RENTAL'
            WHEN '영구임대' THEN 'PERMANENT_RENTAL'
            WHEN '5년임대' THEN 'PUBLIC_RENTAL_5Y'
            WHEN '10년임대' THEN 'PUBLIC_RENTAL_10Y'
            WHEN '50년임대' THEN 'PUBLIC_RENTAL_50Y'
            WHEN '장기전세' THEN 'LONG_TERM_JEONSE'
            WHEN '통합공공임대' THEN 'INTEGRATED_PUBLIC_RENTAL'
            WHEN '재개발임대' THEN 'REDEVELOPMENT_RENTAL'
            WHEN '기타' THEN 'ETC'
        END
    GROUP BY link.housing_complex_id
)
UPDATE housing_complexes complex
SET deposit_min = prices.deposit_min,
    deposit_max = prices.deposit_max,
    monthly_rent_min = prices.monthly_rent_min,
    monthly_rent_max = prices.monthly_rent_max
FROM source_prices prices
WHERE complex.id = prices.housing_complex_id;
