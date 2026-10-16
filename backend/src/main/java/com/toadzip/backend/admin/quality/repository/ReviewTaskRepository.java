package com.toadzip.backend.admin.quality.repository;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.domain.ReviewTaskStatus;
import com.toadzip.backend.admin.quality.dto.ReviewTask;
import com.toadzip.backend.admin.quality.dto.SaveReviewTask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewTaskRepository {
    private static final String SOURCE = """
            SELECT source.*, COALESCE(status <> 'DONE' AND due_date < :today, false) AS overdue,
            """ + QualityQueueRepository.SOURCE_CHANGED + """
             AS source_changed FROM (
              SELECT task.*, task.resource_id AS id, COALESCE(c.name, a.name) AS name
              FROM admin_quality_tasks task
              LEFT JOIN housing_complexes c ON task.resource = 'COMPLEX' AND c.id = task.resource_id
              LEFT JOIN announcements a ON task.resource = 'ANNOUNCEMENT' AND a.id = task.resource_id
              WHERE (c.id IS NOT NULL AND NOT c.admin_deleted) OR (a.id IS NOT NULL AND NOT a.admin_deleted)
            ) source
            """;
    private static final String FILTER = """
             WHERE (:mine = false OR assignee = :actor) AND (:state = '' OR status = :state)
               AND (:overdue = false OR overdue) AND (:alerts = false OR
                    (status <> 'DONE' AND (overdue OR source_changed)))
            """;
    private final JdbcClient jdbc;

    public ReviewTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> assignees() {
        return jdbc.sql("SELECT login_identifier FROM admin_accounts WHERE role = 'ADMIN' "
                + "ORDER BY login_identifier LIMIT 1000").query(String.class).list();
    }

    public Optional<ReviewTask> find(QualityResource resource, long id, LocalDate today) {
        return jdbc.sql("SELECT * FROM (" + SOURCE + ") task WHERE resource = :resource AND id = :id")
                .param("resource", resource.name()).param("id", id).param("today", today)
                .query((row, index) -> task(row)).optional();
    }

    public AdminPage<ReviewTask> search(String actor, boolean mine, String state, boolean overdue,
            boolean alerts, int page, int size, LocalDate today) {
        String from = " FROM (" + SOURCE + ") task " + FILTER;
        long count = filtered("SELECT count(*)" + from, actor, mine, state, overdue, alerts, today)
                .query(Long.class).single();
        var items = filtered("SELECT *" + from + """
                 ORDER BY CASE WHEN status = 'DONE' THEN 1 ELSE 0 END, overdue DESC, source_changed DESC,
                    due_date ASC NULLS LAST, updated_at DESC, resource, id DESC LIMIT :size OFFSET :offset
                """, actor, mine, state, overdue, alerts, today)
                .param("size", size).param("offset", (long) page * size).query((row, index) -> task(row)).list();
        return new AdminPage<>(items, page, ((long) page + 1) * size < count, count,
                Math.toIntExact((count + size - 1) / size));
    }

    private JdbcClient.StatementSpec filtered(String sql, String actor, boolean mine, String state,
            boolean overdue, boolean alerts, LocalDate today) {
        return jdbc.sql(sql).param("actor", actor).param("mine", mine).param("state", state)
                .param("overdue", overdue).param("alerts", alerts).param("today", today);
    }

    public boolean save(QualityResource resource, long id, SaveReviewTask request, String actor) {
        String sql = """
                INSERT INTO admin_quality_tasks(resource, resource_id, revision, assignee, due_date,
                    status, note, actor)
                VALUES (:resource, :id, 1, :assignee, :due, :state, :note, :actor)
                ON CONFLICT (resource, resource_id) DO NOTHING
                """;
        if (request.expectedRevision() > 0) {
            sql = """
                    UPDATE admin_quality_tasks SET revision = revision + 1, assignee = :assignee, due_date = :due,
                        status = :state, note = :note, actor = :actor, updated_at = CURRENT_TIMESTAMP
                    WHERE resource = :resource AND resource_id = :id AND revision = :revision
                    """;
        }
        var statement = jdbc.sql(sql).param("resource", resource.name()).param("id", id)
                .param("assignee", request.assignee()).param("due", request.dueDate())
                .param("state", request.status().name()).param("note", request.note()).param("actor", actor);
        if (request.expectedRevision() > 0) {
            statement = statement.param("revision", request.expectedRevision());
        }
        return statement.update() == 1;
    }

    private ReviewTask task(ResultSet row) throws SQLException {
        return new ReviewTask(QualityResource.valueOf(row.getString("resource")), row.getLong("id"),
                row.getString("name"), row.getLong("revision"), row.getString("assignee"),
                row.getObject("due_date", LocalDate.class), ReviewTaskStatus.valueOf(row.getString("status")),
                row.getString("note"), row.getString("actor"), row.getTimestamp("updated_at").toInstant(),
                row.getBoolean("overdue"), row.getBoolean("source_changed"));
    }
}
