package com.toadzip.backend.feedback.repository;

import com.toadzip.backend.feedback.domain.Feedback;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 의견에 붙은 단지·공고를 찾는다. 삭제·비공개 처리된 행도 운영자가 열 수 있어 포함한다.
 * 병합으로 지워진 단지 ID는 남아 있는 별칭을 따라 대표 단지로 읽는다. 병합을 되돌려 단지가 돌아오면 그 단지를 먼저 읽는다.
 */
@Repository
public class FeedbackTargetRepository {

    public static final String COMPLEX = "COMPLEX";
    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";

    private static final String RESOLVE_COMPLEX = """
            SELECT id FROM (
                SELECT id, 0 AS priority FROM housing_complexes WHERE id = :id
                UNION ALL
                SELECT housing_complex_id, 1 FROM housing_complex_aliases WHERE id = :id
            ) target ORDER BY priority LIMIT 1
            """;
    private static final String COMPLEX_TARGETS = """
            SELECT complex.id AS requested_id, complex.id, complex.name
            FROM housing_complexes complex WHERE complex.id IN (:ids)
            UNION ALL
            SELECT alias.id, complex.id, complex.name
            FROM housing_complex_aliases alias JOIN housing_complexes complex ON complex.id = alias.housing_complex_id
            WHERE alias.id IN (:ids) AND NOT EXISTS (SELECT 1 FROM housing_complexes own WHERE own.id = alias.id)
            """;
    private static final String ANNOUNCEMENT_TARGETS = """
            SELECT id AS requested_id, id, name FROM announcements WHERE id IN (:ids)
            """;

    private final JdbcClient jdbc;

    public FeedbackTargetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 지금 그 대상을 가리키는 ID. 병합된 단지는 대표 단지 ID이고, 찾지 못하면 비어 있다. */
    public Optional<Long> resolve(String resource, long id) {
        String sql = switch (resource) {
            case COMPLEX -> RESOLVE_COMPLEX;
            case ANNOUNCEMENT -> "SELECT id FROM announcements WHERE id = :id";
            default -> throw new IllegalArgumentException("알 수 없는 관련 대상입니다: " + resource);
        };
        return jdbc.sql(sql).param("id", id).query(Long.class).list().stream().findFirst();
    }

    /** 의견 목록의 대상을 종류별 한 번의 조회로 읽는다. 대상이 없거나 행을 찾지 못하면 null을 준다. */
    public Function<Feedback, Target> targets(List<Feedback> feedbacks) {
        Map<String, Target> targets = new HashMap<>();
        for (String resource : List.of(COMPLEX, ANNOUNCEMENT)) {
            List<Long> ids = feedbacks.stream()
                    .filter(feedback -> resource.equals(feedback.getRelatedResource()) && feedback.getRelatedId() != null)
                    .map(Feedback::getRelatedId).distinct().toList();
            if (ids.isEmpty()) {
                continue;
            }
            jdbc.sql(resource.equals(COMPLEX) ? COMPLEX_TARGETS : ANNOUNCEMENT_TARGETS).param("ids", ids)
                    .query((row, index) -> Map.entry(key(resource, row.getLong("requested_id")),
                            new Target(row.getLong("id"), row.getString("name"))))
                    .list().forEach(entry -> targets.put(entry.getKey(), entry.getValue()));
        }
        return feedback -> targets.get(key(feedback.getRelatedResource(), feedback.getRelatedId()));
    }

    private static String key(String resource, Long id) {
        return resource + ":" + id;
    }

    /** 지금 그 대상을 가리키는 ID와 이름. */
    public record Target(long id, String name) {
    }
}
