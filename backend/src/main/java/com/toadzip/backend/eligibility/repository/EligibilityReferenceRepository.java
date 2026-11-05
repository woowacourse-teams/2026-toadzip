package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.EligibilityCatalog;
import com.toadzip.backend.eligibility.domain.IncomeStandard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class EligibilityReferenceRepository {
    private final JdbcTemplate jdbc;

    public EligibilityReferenceRepository(JdbcTemplate jdbc) {
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

    public List<EligibilityCatalog.Fact> facts() {
        return jdbc.query(
                """
                SELECT code, label, fact_kind, value_type, subject_level, meaning_status, meaning_note,
                    depends_on
                FROM eligibility_facts ORDER BY code
                """,
                (result, row) -> {
                    List<String> dependencies = List.of();
                    java.sql.Array array = result.getArray("depends_on");
                    if (array != null) {
                        try {
                            dependencies = List.of((String[]) array.getArray());
                        } finally {
                            array.free();
                        }
                    }
                    return new EligibilityCatalog.Fact(
                            result.getString("code"),
                            result.getString("label"),
                            result.getString("fact_kind"),
                            result.getString("value_type"),
                            result.getString("subject_level"),
                            result.getString("meaning_status"),
                            result.getString("meaning_note"),
                            dependencies);
                });
    }

    public List<EligibilityCatalog.InputGap> gaps() {
        return jdbc.query(
                "SELECT * FROM input_gaps ORDER BY code",
                (result, row) ->
                        new EligibilityCatalog.InputGap(
                                result.getString("code"),
                                result.getString("label"),
                                result.getString("needed_fact"),
                                result.getString("subject_description"),
                                result.getString("why_not_computable"),
                                result.getString("follow_up_question")));
    }
}
