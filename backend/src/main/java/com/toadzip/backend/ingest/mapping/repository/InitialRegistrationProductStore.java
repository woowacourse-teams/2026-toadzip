package com.toadzip.backend.ingest.mapping.repository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InitialRegistrationProductStore {

    private final JdbcClient jdbc;

    public List<Product> findProducts() {
        return jdbc.sql("""
                SELECT id, source_complex_identifier AS identifier, name, road_address, pnu,
                       supply_type, provider, city_county_district_code AS district, admin_deleted AS deleted
                FROM housing_complexes ORDER BY id
                """).query(Product.class).list();
    }

    public List<Link> findLinks() {
        return jdbc.sql("""
                SELECT source_complex_identifier AS identifier, housing_complex_id AS complex_id
                FROM myhome_complex_links
                """).query(Link.class).list();
    }

    public record Product(long id, String identifier, String name, String roadAddress, String pnu,
                          String supplyType, String provider, String district, boolean deleted) {
    }

    public record Link(String identifier, long complexId) {
    }
}
