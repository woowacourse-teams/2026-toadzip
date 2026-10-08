ALTER TABLE housing_types
    ADD COLUMN basic_deposit BIGINT,
    ADD COLUMN basic_monthly_rent BIGINT,
    ADD COLUMN rental_condition_collected_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE housing_types
    ADD CONSTRAINT ck_housing_type_basic_rental_condition CHECK (
        (basic_deposit IS NULL OR basic_deposit >= 0)
        AND (basic_monthly_rent IS NULL OR basic_monthly_rent >= 0)
        AND ((basic_deposit IS NULL AND basic_monthly_rent IS NULL)
             OR rental_condition_collected_at IS NOT NULL)
    );

-- 정제에서 사용하는 길이 접두어 원천키를 재현한다. 형명이나 면적 하나로 연결하지 않는다.
WITH source_parts AS (
    SELECT source.*,
           ARRAY[
               hsmp_sn::text, BTRIM(pnu), BTRIM(suply_ty_nm), BTRIM(style_nm),
               trim_scale(round(suply_prvuse_ar, 4))::text,
               trim_scale(round(suply_cmnuse_ar, 4))::text
           ] AS key_parts
    FROM myhome_complex_source_rows source
), source_prices AS (
    SELECT (SELECT string_agg(
                CASE WHEN part IS NULL THEN '-1:' ELSE length(part)::text || ':' || part END,
                '' ORDER BY ordinal)
            FROM unnest(key_parts) WITH ORDINALITY AS parts(part, ordinal)) AS source_key,
           CASE WHEN bass_rent_gtn >= 0 THEN bass_rent_gtn END AS deposit,
           CASE WHEN bass_mt_rntchrg >= 0 THEN bass_mt_rntchrg END AS monthly_rent,
           collected_at
    FROM source_parts
), unambiguous_prices AS (
    SELECT source_key, min(deposit) AS deposit, min(monthly_rent) AS monthly_rent,
           max(collected_at) AS collected_at
    FROM source_prices
    GROUP BY source_key
    HAVING count(DISTINCT jsonb_build_array(deposit, monthly_rent)) = 1
)
UPDATE housing_types housing_type
SET basic_deposit = prices.deposit,
    basic_monthly_rent = prices.monthly_rent,
    rental_condition_collected_at = prices.collected_at
FROM unambiguous_prices prices
WHERE housing_type.source_housing_type_identifier = prices.source_key
  AND prices.collected_at IS NOT NULL;
