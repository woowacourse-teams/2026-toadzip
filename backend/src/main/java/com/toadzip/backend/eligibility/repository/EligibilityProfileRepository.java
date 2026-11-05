package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.InputFact;
import com.toadzip.backend.eligibility.domain.InputValue;
import com.toadzip.backend.eligibility.domain.ValueState;
import com.toadzip.backend.eligibility.exception.EligibilityConflictException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class EligibilityProfileRepository {
    private static final List<InputFact> REGULAR =
            Arrays.stream(InputFact.values()).filter(fact -> !fact.special()).toList();
    private final JdbcClient jdbc;

    public EligibilityProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public EligibilityProfile find(long userId) {
        Optional<EligibilityProfile> profile =
                jdbc.sql("SELECT * FROM eligibility_profiles WHERE user_id = ?")
                        .param(userId)
                        .query(this::map)
                        .optional();
        if (profile.isEmpty()) {
            return EligibilityProfile.unknown();
        }
        EligibilityProfile current = profile.get();
        Map<InputFact, InputValue> facts = new EnumMap<>(current.facts());
        jdbc.sql(
                        "SELECT status_code, value, state FROM eligibility_profile_special_statuses"
                            + " WHERE profile_id = ?")
                .param(current.id())
                .query(
                        (result, index) -> {
                            facts.put(
                                    InputFact.valueOf(result.getString("status_code")),
                                    new InputValue(
                                            ValueState.valueOf(result.getString("state")),
                                            result.getObject("value", Boolean.class)));
                            return result.getString("status_code");
                        })
                .list();
        return new EligibilityProfile(current.id(), current.version(), current.updatedAt(), facts);
    }

    public void save(long userId, long expectedVersion, EligibilityProfile profile, Instant now) {
        Map<String, Object> values = values(userId, expectedVersion, profile, now);
        long id = write(values, expectedVersion).orElseThrow(EligibilityConflictException::new);
        for (InputFact fact : InputFact.values()) {
            if (!fact.special()) {
                continue;
            }
            jdbc.sql(
                            """
                            INSERT INTO eligibility_profile_special_statuses (profile_id, status_code, value, state,
                            updated_at)
                            VALUES (:id, :code, :value, :state, :now)
                            ON CONFLICT (profile_id, status_code) DO UPDATE
                            SET value = EXCLUDED.value, state = EXCLUDED.state, updated_at = EXCLUDED.updated_at
                            """)
                    .param("id", id)
                    .param("code", fact.column())
                    .param("value", profile.value(fact).value())
                    .param("state", profile.value(fact).state().name())
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .update();
        }
    }

    private Optional<Long> write(Map<String, Object> values, long expectedVersion) {
        if (expectedVersion == 0) {
            String columns =
                    REGULAR.stream()
                            .map(fact -> fact.column() + ", " + fact.column() + "_state")
                            .collect(Collectors.joining(", "));
            String bindings =
                    REGULAR.stream()
                            .map(fact -> ":" + fact.column() + ", :" + fact.column() + "_state")
                            .collect(Collectors.joining(", "));
            return jdbc.sql(
                            "INSERT INTO eligibility_profiles (user_id, version, created_at,"
                                + " updated_at, "
                                    + columns
                                    + ") VALUES (:userId, 1, :now, :now, "
                                    + bindings
                                    + ") ON CONFLICT (user_id) DO NOTHING RETURNING id")
                    .params(values)
                    .query(Long.class)
                    .optional();
        }
        String assignments =
                REGULAR.stream()
                        .map(
                                fact ->
                                        fact.column()
                                                + " = :"
                                                + fact.column()
                                                + ", "
                                                + fact.column()
                                                + "_state = :"
                                                + fact.column()
                                                + "_state")
                        .collect(Collectors.joining(", "));
        return jdbc.sql(
                        "UPDATE eligibility_profiles SET "
                                + assignments
                                + ", version = version + 1, updated_at = :now WHERE user_id ="
                                + " :userId AND version = :expectedVersion RETURNING id")
                .params(values)
                .query(Long.class)
                .optional();
    }

    private Map<String, Object> values(
            long userId, long expectedVersion, EligibilityProfile profile, Instant now) {
        Map<String, Object> values = new HashMap<>();
        values.put("userId", userId);
        values.put("expectedVersion", expectedVersion);
        values.put("now", now.atOffset(ZoneOffset.UTC));
        for (InputFact fact : REGULAR) {
            values.put(fact.column(), profile.value(fact).value());
            values.put(fact.column() + "_state", profile.value(fact).state().name());
        }
        return values;
    }

    private EligibilityProfile map(ResultSet result, int index) throws SQLException {
        Map<InputFact, InputValue> facts = new EnumMap<>(InputFact.class);
        for (InputFact fact : REGULAR) {
            facts.put(
                    fact,
                    new InputValue(
                            ValueState.valueOf(result.getString(fact.column() + "_state")),
                            readValue(result, fact)));
        }
        return new EligibilityProfile(
                result.getLong("id"),
                result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class).toInstant(),
                facts);
    }

    private Object readValue(ResultSet result, InputFact fact) throws SQLException {
        return switch (fact.valueType()) {
            case DATE -> result.getObject(fact.column(), LocalDate.class);
            case BOOLEAN -> result.getObject(fact.column(), Boolean.class);
            case INTEGER, KRW, MONTHS -> result.getObject(fact.column(), Long.class);
            case CODE, REGION -> result.getString(fact.column());
        };
    }
}
