package com.toadzip.backend.region.repository;

import com.toadzip.backend.housing.domain.MapCoordinate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NeighborhoodCoordinateRepository {

    private final JdbcClient jdbcClient;
    private final RegionCodeResolver regionCodeResolver;

    public NeighborhoodCoordinateRepository(JdbcClient jdbcClient, RegionCodeResolver regionCodeResolver) {
        this.jdbcClient = jdbcClient;
        this.regionCodeResolver = regionCodeResolver;
    }

    /** A navigation point derived from public housing locations, not an official boundary centroid. */
    public Optional<MapCoordinate> findByCode(String code) {
        if (code == null || !code.matches("[0-9]{8}00")) {
            return Optional.empty();
        }
        var codes = regionCodeResolver.filterCodes(code);
        if (codes.isEmpty()) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                SELECT (MIN(latitude) + MAX(latitude)) / 2 AS latitude,
                       (MIN(longitude) + MAX(longitude)) / 2 AS longitude
                FROM housing_complexes
                WHERE admin_deleted = false AND SUBSTRING(legal_dong_code, 1, 8) IN (:prefixes)
                HAVING COUNT(*) > 0
                """)
                .param("prefixes", codes.orElseThrow().stream().map(value -> value.substring(0, 8)).toList())
                .query((row, index) -> new MapCoordinate(row.getBigDecimal("latitude"), row.getBigDecimal("longitude")))
                .optional();
    }
}
