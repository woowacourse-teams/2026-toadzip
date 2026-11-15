package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.ingest.source.repository.IngestSourceRows;

public final class SupplyReviewSql {
    /**
     * 검토 대상의 지금 값. "row:{id}"는 공고 주택형 값과 그 주택형 모집수(주택형의 모든 칸을 멤버로 둔 WINNER 수),
     * "cell:{id}"는 칸의 공급대상과 그 칸만의 WINNER·RESERVE 수, 임대조건 금액이다. 관리자가 제외한 옛 공급행은 대상이 아니다.
     */
    public static final String TARGET_VALUES = """
            SELECT r.announcement_id, 'row:' || r.id AS target,
              jsonb_build_object('complexName', ac.source_complex_name, 'name', r.housing_type_name,
                'housingComplexId', ac.housing_complex_id, 'housingTypeId', r.housing_type_id,
                'exclusiveArea', trim_scale(r.exclusive_area), 'supplyArea', trim_scale(r.supply_area),
                'supplyPnu', r.supply_pnu, 'expectedMoveInMonth', r.expected_move_in_month,
                'category', r.supply_category, 'displayOrder', r.display_order,
                'householdCount', (SELECT q.household_count FROM supply_quotas q
                  WHERE q.announcement_id = r.announcement_id AND q.kind = 'WINNER'
                    AND EXISTS (SELECT 1 FROM supply_row_targets c WHERE c.supply_row_id = r.id)
                    AND NOT EXISTS (SELECT 1 FROM supply_quota_members m JOIN supply_row_targets c ON c.id = m.supply_row_target_id
                      WHERE m.supply_quota_id = q.id AND c.supply_row_id <> r.id)
                    AND NOT EXISTS (SELECT 1 FROM supply_row_targets c WHERE c.supply_row_id = r.id AND NOT EXISTS (
                      SELECT 1 FROM supply_quota_members m WHERE m.supply_quota_id = q.id AND m.supply_row_target_id = c.id))
                  ORDER BY q.id LIMIT 1)) AS values
            FROM supply_rows r JOIN announcement_complexes ac ON ac.id = r.announcement_complex_id
            WHERE NOT r.admin_excluded
            UNION ALL
            SELECT r.announcement_id, 'cell:' || c.id,
              jsonb_build_object('label', t.label, 'applicationCondition', t.application_condition,
                'winnerCount', (SELECT q.household_count FROM supply_quotas q
                  JOIN supply_quota_members m ON m.supply_quota_id = q.id AND m.supply_row_target_id = c.id
                  WHERE q.kind = 'WINNER' AND NOT EXISTS (SELECT 1 FROM supply_quota_members o
                    WHERE o.supply_quota_id = q.id AND o.supply_row_target_id <> c.id) ORDER BY q.id LIMIT 1),
                'reserveCount', (SELECT q.household_count FROM supply_quotas q
                  JOIN supply_quota_members m ON m.supply_quota_id = q.id AND m.supply_row_target_id = c.id
                  WHERE q.kind = 'RESERVE' AND NOT EXISTS (SELECT 1 FROM supply_quota_members o
                    WHERE o.supply_quota_id = q.id AND o.supply_row_target_id <> c.id) ORDER BY q.id LIMIT 1),
                'rentalDeposit', trim_scale(l.rental_deposit)::text, 'monthlyRent', trim_scale(l.monthly_rent)::text,
                'convertedDeposit', trim_scale(l.converted_deposit)::text)
            FROM supply_row_targets c JOIN supply_rows r ON r.id = c.supply_row_id
              JOIN announcement_supply_targets t ON t.id = c.supply_target_id
              LEFT JOIN lease_term_members lm ON lm.supply_row_target_id = c.id
              LEFT JOIN lease_terms l ON l.id = lm.lease_terms_id
            WHERE NOT r.admin_excluded
            """;

    public static final String CURRENT_EVIDENCE = """
            (SELECT COALESCE(jsonb_agg(jsonb_build_object('category', saved -> 'category',
                'sourceKey', saved -> 'sourceKey', 'fields', saved -> 'fields', 'rows', (
              SELECT COALESCE(jsonb_agg(projected ORDER BY projected::text), '[]'::jsonb) FROM (
                SELECT (SELECT COALESCE(jsonb_object_agg(field, raw -> field) FILTER (WHERE jsonb_exists(raw, field)), '{}'::jsonb)
                  FROM jsonb_array_elements_text(saved -> 'fields') field) AS projected FROM (
            """ + IngestSourceRows.NORMALIZED_ROWS + """
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
