package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.location.domain.NormalizedRoadAddress;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
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

    public List<Product> findRegistrationCandidates(String identifier, Collection<String> pnus,
            Collection<String> names, Collection<String> addresses) {
        return jdbc.sql("""
                SELECT id, source_complex_identifier AS identifier, name, road_address, pnu,
                       supply_type, provider, city_county_district_code AS district, admin_deleted AS deleted
                FROM housing_complexes
                WHERE source_complex_identifier = :identifier
                   OR id IN (SELECT housing_complex_id FROM myhome_complex_links
                             WHERE source_complex_identifier = :identifier)
                   OR pnu IN (:pnus) OR name IN (:names)
                   OR regexp_replace(normalize(road_address, NFKC),
                       '[[:space:]]|[(][^()]*[)]', '', 'g') IN (:addresses)
                ORDER BY id
                """).param("identifier", identifier).param("pnus", nonEmpty(pnus)).param("names", nonEmpty(names))
                .param("addresses", nonEmpty(addresses.stream().filter(Objects::nonNull)
                        .filter(address -> !address.isBlank())
                        .map(address -> new NormalizedRoadAddress(address).value().replaceAll("\\s|\\([^()]*\\)", ""))
                        .toList()))
                .query(Product.class).list();
    }

    public List<Link> findLinks(String identifier) {
        return jdbc.sql("""
                SELECT source_complex_identifier AS identifier, housing_complex_id AS complex_id
                FROM myhome_complex_links WHERE source_complex_identifier = :identifier
                """).param("identifier", identifier).query(Link.class).list();
    }

    private List<String> nonEmpty(Collection<String> values) {
        var result = values.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().toList();
        if (result.isEmpty()) {
            return List.of("");
        }
        return result;
    }

    public record Product(long id, String identifier, String name, String roadAddress, String pnu,
                          String supplyType, String provider, String district, boolean deleted) {
    }

    public record Link(String identifier, long complexId) {
    }
}
