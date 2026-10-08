package com.toadzip.backend.streetview;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class StreetViewFixtures {
    private StreetViewFixtures() {
    }

    public static long complex(JdbcClient jdbc) {
        return jdbc.sql("""
                INSERT INTO housing_complexes (source_complex_identifier, name, admin_deleted, supply_type, provider,
                    road_address, pnu, legal_dong_code, province_code, city_county_district_code, latitude, longitude,
                    total_household_count, parking_space_count)
                VALUES (:identifier, '어바니엘 위드 더 스타일 충정로', false, '행복주택', 'LH',
                    '서울특별시 서대문구 경기대로 26-26', 'pnu', '11110', '11', '110',
                    37.561443, 126.962715, 10, 10) RETURNING id
                """).param("identifier", "STREET-VIEW-" + UUID.randomUUID()).query(Long.class).single();
    }

    public static void policy(JdbcClient jdbc, boolean enabled) {
        jdbc.sql("""
                INSERT INTO street_view_policies (id, enabled, version, change_reason, updated_by, updated_at)
                VALUES (1, :enabled, 0, '테스트 초기 정책', 'SYSTEM_MIGRATION', CURRENT_TIMESTAMP)
                """).param("enabled", enabled).update();
    }
}
