package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.admin.quality.repository.QualityComparisonRepository;

public final class SupplyReviewSql {
    public static final String TARGET_VALUES = """
            SELECT r.announcement_id, 'row:' || r.id AS target,
              jsonb_build_object(
                'connection', jsonb_build_object('complexId', r.housing_complex_id, 'typeId', r.housing_type_id,
                  'complexName', c.name, 'address', c.road_address, 'deleted', c.admin_deleted,
                  'typeName', h.name, 'exclusiveArea', h.exclusive_area, 'supplyArea', h.supply_area),
                'names', jsonb_build_object('complexName', r.source_complex_name,
                  'typeName', r.source_housing_type_name, 'pnu', r.supply_pnu),
                'areas', jsonb_build_object('exclusiveArea', CASE WHEN r.area_override
                  THEN r.announcement_exclusive_area ELSE h.exclusive_area END,
                  'supplyArea', CASE WHEN r.area_override THEN r.announcement_supply_area ELSE h.supply_area END),
                'householdCount', r.total_supply_household_count,
                'sharedRecruitment', jsonb_build_object('groupId', r.supply_group_id, 'count', g.shared_household_count),
                'moveInMonth', r.expected_move_in_month, 'category', r.supply_category,
                'excluded', r.admin_excluded, 'displayOrder', r.display_order,
                'targetCountsComparable', r.target_counts_comparable) AS values
            FROM supply_rows r LEFT JOIN housing_complexes c ON c.id = r.housing_complex_id
              LEFT JOIN housing_types h ON h.id = r.housing_type_id
              LEFT JOIN supply_row_groups g ON g.id = r.supply_group_id
            UNION ALL
            SELECT r.announcement_id, 'target:' || t.id,
              jsonb_build_object('target', t.target, 'supplyRank', t.supply_rank,
                'householdCount', t.supply_household_count, 'reserveCount', t.reserve_count,
                'deposit', trim_scale(t.rental_deposit)::text, 'rent', trim_scale(t.monthly_rent)::text,
                'convertedDeposit', trim_scale(t.converted_deposit)::text,
                'condition', t.application_condition, 'excluded', t.admin_excluded,
                'parentExcluded', r.admin_excluded, 'displayOrder', t.display_order)
            FROM supply_targets t JOIN supply_rows r ON r.id = t.supply_row_id
            """;

    public static final String CURRENT_EVIDENCE = """
            (SELECT COALESCE(jsonb_agg(jsonb_build_object('category', saved -> 'category',
                'sourceKey', saved -> 'sourceKey', 'fields', saved -> 'fields', 'rows', (
              SELECT COALESCE(jsonb_agg(projected ORDER BY projected::text), '[]'::jsonb) FROM (
                SELECT (SELECT COALESCE(jsonb_object_agg(field, raw -> field) FILTER (WHERE jsonb_exists(raw, field)), '{}'::jsonb)
                  FROM jsonb_array_elements_text(saved -> 'fields') field) AS projected FROM (
            """ + QualityComparisonRepository.SOURCE_ROWS + """
                ) source WHERE category = saved ->> 'category' AND source_key = saved ->> 'sourceKey'
              ) projected_rows
            )) ORDER BY ord), '[]'::jsonb)
             FROM jsonb_array_elements(review.evidence::jsonb) WITH ORDINALITY evidence(saved, ord))
            """;

    public static final String EVALUATED = """
            SELECT evaluated.*, CASE WHEN current_values IS NULL
                OR NOT (current_values @> checked_values::jsonb)
                OR current_evidence IS DISTINCT FROM evidence::jsonb THEN 'STALE' ELSE outcome END AS status
            FROM (
              SELECT review.*, current.values AS current_values,
            """ + CURRENT_EVIDENCE + """
                AS current_evidence,
                row_number() OVER (PARTITION BY review.announcement_id, review.target ORDER BY review.id DESC) = 1 AS latest
              FROM admin_supply_reviews review LEFT JOIN (
            """ + TARGET_VALUES + """
              ) current ON current.announcement_id = review.announcement_id AND current.target = review.target
            ) evaluated
            """;
    private SupplyReviewSql() { }
}
