package com.toadzip.backend.basiccomparison.repository;

import com.toadzip.backend.basiccomparison.domain.IncomeStandard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

@Repository
public class ComparisonReferenceRepository {
    private final JdbcTemplate jdbc;

    public ComparisonReferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, IncomeStandard> incomeStandards() {
        Map<Long, Map<Long, Long>> amounts = new HashMap<>();
        jdbc.query(
                "SELECT table_id, household_size, amount_100_krw FROM income_standard_values",
                result -> {
                    amounts.computeIfAbsent(result.getLong("table_id"), key -> new HashMap<>())
                            .put(
                                    result.getLong("household_size"),
                                    result.getLong("amount_100_krw"));
                });
        Map<Long, IncomeStandard> standards = new HashMap<>();
        jdbc.query(
                "SELECT * FROM income_standard_tables",
                result -> {
                    long id = result.getLong("id");
                    standards.put(
                            id,
                            new IncomeStandard(
                                    id,
                                    result.getString("standard_kind"),
                                    result.getInt("base_year"),
                                    result.getString("source_note"),
                                    result.getObject("extra_per_person_krw", Long.class),
                                    result.getLong("max_listed_household_size"),
                                    amounts.getOrDefault(id, Map.of())));
                });
        return Map.copyOf(standards);
    }
}
