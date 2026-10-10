package com.toadzip.backend.housing.repository;

public final class ComplexVerificationSql {
    // Both the detail and list evaluate the same selected values against the immutable review snapshot.
    public static final String CURRENT_VALUES = """
            jsonb_build_object(
              'NAME', c.name,
              'ADDRESS', jsonb_build_object('roadAddress', c.road_address, 'pnu', c.pnu,
                'legalDongCode', c.legal_dong_code, 'provinceCode', c.province_code,
                'cityCountyDistrictCode', c.city_county_district_code),
              'LOCATION', jsonb_build_array(c.latitude, c.longitude),
              'AGENCY', c.provider, 'RENTAL_TYPE', c.supply_type,
              'HOUSEHOLD_COUNT', c.total_household_count)
            """;

    public static final String LATEST_REVIEW = """
             LEFT JOIN LATERAL (
               SELECT id, outcome, checked_values FROM housing_complex_reviews
               WHERE housing_complex_id = c.id ORDER BY id DESC LIMIT 1
             ) verification_review ON true
            """;

    public static final String STATUS = "CASE WHEN verification_review.id IS NULL THEN 'UNREVIEWED' "
            + "WHEN " + CURRENT_VALUES + " @> verification_review.checked_values THEN verification_review.outcome "
            + "ELSE 'STALE' END";

    private ComplexVerificationSql() { }
}
