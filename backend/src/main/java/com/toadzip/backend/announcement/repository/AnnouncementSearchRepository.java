package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.admin.dto.AdminSearch;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.AnnouncementHousingComplex;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AnnouncementReleaseStatus;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.AnnouncementSort;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.ClosedAnnouncementFollowUp;
import com.toadzip.backend.global.persistence.LegacyStoredValue;
import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaExpression;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class AnnouncementSearchRepository {

    private static final String ADMIN_OVERVIEW_SQL = """
            WITH overview AS (
                SELECT a.id, COALESCE(%s, 'UNKNOWN') AS application_status,
                    %s AS schedule_missing, %s AS supply_missing,
                    a.release_status = 'UNCONFIRMED' AS unconfirmed, %s AS processing_stage
                FROM announcements a
                %s
            )
            """.formatted(ApplicationScheduleSql.status("a", null),
                    AnnouncementAttentionSql.scheduleMissing("a"), AnnouncementAttentionSql.supplyMissing("a"),
                    AnnouncementProcessingStageSql.stage("a"), AnnouncementProcessingStageSql.joins("a"));
    /** 마감 전은 접수마감·공고취소가 아닌 공고다. 일정 미정·조건부 접수도 처리할 일에 들어간다. */
    private static final String OPEN_SQL = "o.application_status NOT IN ('CLOSED', 'CANCELLED')";
    private static final String OPEN_STAGE_COUNTS_SQL = ADMIN_OVERVIEW_SQL + """
            SELECT o.processing_stage AS stage, COUNT(*) AS count
            FROM overview o JOIN announcements a ON a.id = o.id WHERE a.admin_deleted = false AND
            """ + OPEN_SQL + " GROUP BY o.processing_stage";
    private static final String OPEN_NOT_EXTRACTED_SQL = ADMIN_OVERVIEW_SQL + """
            SELECT o.id FROM overview o JOIN announcements a ON a.id = o.id
            WHERE a.admin_deleted = false AND o.processing_stage = 'NOT_EXTRACTED' AND
            """ + OPEN_SQL;
    private static final String ADMIN_COUNTS_SQL = ADMIN_OVERVIEW_SQL + """
            SELECT COUNT(*) FILTER (WHERE application_status = 'BEFORE_APPLICATION') AS upcoming,
                COUNT(*) FILTER (WHERE application_status = 'APPLYING') AS applying,
                COUNT(*) FILTER (WHERE application_status = 'CLOSED') AS closed,
                COUNT(*) FILTER (WHERE application_status = 'CONDITIONAL') AS conditional,
                COUNT(*) FILTER (WHERE application_status = 'CANCELLED') AS cancelled,
                COUNT(*) FILTER (WHERE application_status = 'UNKNOWN') AS unknown,
                COUNT(*) FILTER (WHERE schedule_missing OR supply_missing OR unconfirmed) AS attention,
                COUNT(*) FILTER (WHERE schedule_missing) AS schedule_missing,
                COUNT(*) FILTER (WHERE supply_missing) AS supply_missing,
                COUNT(*) FILTER (WHERE unconfirmed) AS unconfirmed
            FROM overview o JOIN announcements a ON a.id = o.id WHERE a.admin_deleted = false
            """;

    private final EntityManager entityManager;

    public AnnouncementSearchRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * applicationStatus는 접수 상태 하나이거나 OPEN(마감 전)이다. stage는 처리 단계 코드이며 빈 값이면 거르지 않는다.
     */
    public Page<Announcement> searchAdmin(AdminSearch search, long complexId, String status, String attention,
            String stage, LocalDate today) {
        String where = AnnouncementRepository.ADMIN_SEARCH_FROM.replace(" FROM announcements c ",
                " FROM announcements c JOIN overview o ON o.id = c.id ")
                + " AND (:applicationStatus = '' OR o.application_status = :applicationStatus"
                + " OR (:applicationStatus = 'OPEN' AND " + OPEN_SQL + "))"
                + " AND (:stage = '' OR o.processing_stage = :stage)"
                + " AND (:attention = '' OR (:attention = 'ANY' AND (o.schedule_missing OR o.supply_missing"
                + " OR o.unconfirmed)) OR (:attention = 'SCHEDULE_MISSING' AND o.schedule_missing)"
                + " OR (:attention = 'SUPPLY_MISSING' AND o.supply_missing)"
                + " OR (:attention = 'UNCONFIRMED' AND o.unconfirmed))";
        Query count = bindAdmin(entityManager.createNativeQuery(ADMIN_OVERVIEW_SQL + "SELECT count(*)" + where),
                search, complexId, status, attention, today).setParameter("stage", stage);
        Query query = bindAdmin(entityManager.createNativeQuery(ADMIN_OVERVIEW_SQL + "SELECT c.*" + where
                + " ORDER BY c.created_at DESC NULLS LAST, c.id DESC", Announcement.class),
                search, complexId, status, attention, today).setParameter("stage", stage);
        query.setFirstResult(search.page() * search.pageSize()).setMaxResults(search.pageSize());
        @SuppressWarnings("unchecked")
        List<Announcement> items = query.getResultList();
        return new PageImpl<>(items, PageRequest.of(search.page(), search.pageSize()),
                ((Number) count.getSingleResult()).longValue());
    }

    private Query bindAdmin(Query query, AdminSearch search, long complexId, String status, String attention,
            LocalDate today) {
        return query.setParameter("keyword", search.pattern()).setParameter("identifier", search.identifier())
                .setParameter("provider", search.providerCode()).setParameter("providerLegacy", search.providerLegacy())
                .setParameter("rental", search.rentalCode()).setParameter("rentalLegacy", search.rentalLegacy())
                .setParameter("region", search.regionCode()).setParameter("deleted", search.deleted())
                .setParameter("review", search.review()).setParameter("unconfirmed", search.unconfirmed())
                .setParameter("complexId", complexId).setParameter("applicationStatus", status)
                .setParameter("attention", attention).setParameter("today", today);
    }

    public record AdminStatus(long id, String applicationStatus, List<String> attentionReasons,
            String processingStage, LocalDate startDate, LocalDate endDate) { }

    public List<AdminStatus> adminStatuses(List<Long> ids, LocalDate today) {
        if (ids.isEmpty()) { return List.of(); }
        String sql = ADMIN_OVERVIEW_SQL + "SELECT o.*, application_period.start_date, application_period.end_date, "
                + "a.application_schedule_reviewed, a.application_start_date, a.application_end_date"
                + " FROM overview o JOIN announcements a ON a.id = o.id "
                + ApplicationScheduleSql.displayPeriodJoin("a", null) + " WHERE o.id IN (:ids)";
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(sql, Tuple.class)
                .setParameter("today", today).setParameter("ids", ids).getResultList();
        return rows.stream().map(row -> {
            var reasons = new ArrayList<String>();
            if (Boolean.TRUE.equals(row.get("schedule_missing"))) { reasons.add("SCHEDULE_MISSING"); }
            if (Boolean.TRUE.equals(row.get("supply_missing"))) { reasons.add("SUPPLY_MISSING"); }
            if (Boolean.TRUE.equals(row.get("unconfirmed"))) { reasons.add("UNCONFIRMED"); }
            String start = "application_start_date";
            String end = "application_end_date";
            if (Boolean.TRUE.equals(row.get("application_schedule_reviewed"))) {
                start = "start_date";
                end = "end_date";
            }
            return new AdminStatus(((Number) row.get("id")).longValue(), row.get("application_status", String.class),
                    List.copyOf(reasons), row.get("processing_stage", String.class), date(row.get(start)),
                    date(row.get(end)));
        }).toList();
    }

    private LocalDate date(Object value) {
        if (value == null) { return null; }
        if (value instanceof LocalDate date) { return date; }
        return ((java.sql.Date) value).toLocalDate();
    }

    public AdminCounts countForAdmin(LocalDate today) {
        Tuple row = (Tuple) entityManager.createNativeQuery(ADMIN_COUNTS_SQL, Tuple.class)
                .setParameter("today", today).getSingleResult();
        return new AdminCounts(aggregateCount(row, "upcoming"), aggregateCount(row, "applying"),
                aggregateCount(row, "closed"), aggregateCount(row, "conditional"), aggregateCount(row, "cancelled"),
                aggregateCount(row, "unknown"), aggregateCount(row, "attention"),
                aggregateCount(row, "schedule_missing"),
                aggregateCount(row, "supply_missing"), aggregateCount(row, "unconfirmed"));
    }

    /** 휴지통을 뺀 마감 전 공고를 처리 단계별로 센다. 없는 단계도 0으로 넣는다. */
    public Map<String, Long> countOpenStages(LocalDate today) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(OPEN_STAGE_COUNTS_SQL, Tuple.class)
                .setParameter("today", today).getResultList();
        Map<String, Long> counts = new LinkedHashMap<>();
        AnnouncementProcessingStageSql.STAGES.forEach(stage -> counts.put(stage, 0L));
        rows.forEach(row -> counts.put(row.get("stage", String.class), aggregateCount(row, "count")));
        return counts;
    }

    /**
     * 휴지통을 뺀 마감 전 공고 중 PDF 첨부가 있는데 추출한 적이 없는(추출 전) 공고를 최근 공고부터 고른다. excluded는 SQL에서 빼서,
     * 건너뛸 공고가 limit을 다 차지해 그보다 오래된 공고가 영영 뽑히지 않는 일이 없게 한다.
     */
    public List<Long> findOpenNotExtracted(LocalDate today, Collection<Long> excluded, int limit) {
        String exclusion = "";
        if (!excluded.isEmpty()) {
            exclusion = " AND a.id NOT IN (:excluded)";
        }
        String sql = OPEN_NOT_EXTRACTED_SQL + exclusion + " ORDER BY a.id DESC LIMIT :limit";
        Query query = entityManager.createNativeQuery(sql)
                .setParameter("today", today).setParameter("limit", limit);
        if (!excluded.isEmpty()) {
            query.setParameter("excluded", excluded);
        }
        @SuppressWarnings("unchecked")
        List<Number> ids = query.getResultList();
        return ids.stream().map(Number::longValue).toList();
    }

    private long aggregateCount(Tuple row, String column) {
        return row.get(column, Number.class).longValue();
    }

    public List<Announcement> findLatestLeaves(
            AnnouncementSearchCondition condition,
            LocalDate cursorPostedDate,
            Long cursorId,
            int limit
    ) {
        return findLatestLeaves(condition, cursorPostedDate, cursorId, limit, AnnouncementSort.LATEST);
    }

    public List<Announcement> findLatestLeaves(AnnouncementSearchCondition condition, LocalDate cursorPostedDate,
            Long cursorId, int limit, AnnouncementSort sort) {
        boolean ascending = sort == AnnouncementSort.LATEST_ASC;
        HibernateCriteriaBuilder criteriaBuilder = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();
        CriteriaQuery<Announcement> query = criteriaBuilder.createQuery(Announcement.class);
        Root<Announcement> announcement = query.from(Announcement.class);
        List<Predicate> predicates = searchPredicates(criteriaBuilder, query, announcement, condition);
        addCursorPredicate(criteriaBuilder, announcement, cursorPostedDate, cursorId, predicates, ascending);

        query.select(announcement)
                .where(predicates.toArray(Predicate[]::new))
                .orderBy(
                        sortOrder(criteriaBuilder, announcement.get("postedDate"), ascending),
                        sortOrder(criteriaBuilder, announcement.get("id"), ascending)
                );

        return entityManager.createQuery(query)
                .setMaxResults(limit)
                .getResultList();
    }

    public List<AnnouncementDeadlineRow> findSortedLeaves(
            AnnouncementSearchCondition condition, Integer cursorPriority, LocalDate cursorSortDate,
            LocalDate cursorPostedDate, Long cursorId, int limit, AnnouncementSort sort
    ) {
        boolean byPublication = sort == AnnouncementSort.LATEST || sort == AnnouncementSort.LATEST_ASC;
        boolean closedRecent = sort == AnnouncementSort.CLOSED_RECENT;
        boolean ascending = sort != AnnouncementSort.DEADLINE_DESC && !closedRecent;
        boolean publicationAscending = sort == AnnouncementSort.LATEST_ASC || sort == AnnouncementSort.DEADLINE_DESC;
        HibernateCriteriaBuilder builder = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<Announcement> announcement = query.from(Announcement.class);
        List<Predicate> predicates = searchPredicates(builder, query, announcement, condition);
        DeadlineExpressions order = deadlineExpressions(builder, query, announcement, condition);
        if (closedRecent) {
            // 확인된 접수 일정이 있어도 공고의 접수 종료일은 가장 늦은 일정 종료일로 맞춰져 있다.
            order = new DeadlineExpressions(order.priority(), announcement.get("applicationEndDate"));
        }
        if (cursorPriority != null && byPublication) {
            Predicate laterDeadline = builder.or(builder.greaterThan(order.priority(), cursorPriority),
                    builder.and(builder.equal(order.priority(), cursorPriority),
                            builder.greaterThan(order.date(), cursorSortDate)),
                    builder.and(builder.equal(order.priority(), cursorPriority),
                            builder.equal(order.date(), cursorSortDate),
                            afterCursor(builder, announcement.get("id"), cursorId, publicationAscending)));
            predicates.add(builder.or(afterCursor(builder, announcement.get("postedDate"),
                            cursorPostedDate, publicationAscending),
                    builder.and(builder.equal(announcement.get("postedDate"), cursorPostedDate), laterDeadline)));
        }
        if (cursorPriority != null && !byPublication) {
            Predicate laterPublication = builder.or(afterCursor(builder,
                    announcement.get("postedDate"), cursorPostedDate, publicationAscending),
                    builder.and(builder.equal(announcement.get("postedDate"), cursorPostedDate),
                            afterCursor(builder, announcement.get("id"), cursorId, publicationAscending)));
            predicates.add(builder.or(builder.greaterThan(order.priority(), cursorPriority),
                    builder.and(builder.equal(order.priority(), cursorPriority),
                            afterCursor(builder, order.date(), cursorSortDate, ascending)),
                    builder.and(builder.equal(order.priority(), cursorPriority),
                            builder.equal(order.date(), cursorSortDate), laterPublication)));
        }
        List<Order> orders = new ArrayList<>();
        if (byPublication) {
            orders.add(sortOrder(builder, announcement.get("postedDate"), publicationAscending));
        }
        orders.add(builder.asc(order.priority()));
        orders.add(sortOrder(builder, order.date(), ascending));
        if (!byPublication) {
            orders.add(sortOrder(builder, announcement.get("postedDate"), publicationAscending));
        }
        orders.add(sortOrder(builder, announcement.get("id"), publicationAscending));
        query.multiselect(announcement, order.priority(), order.date())
                .where(predicates.toArray(Predicate[]::new))
                .orderBy(orders);
        return entityManager.createQuery(query).setMaxResults(limit).getResultList().stream()
                .map(row -> new AnnouncementDeadlineRow(row.get(0, Announcement.class),
                        row.get(1, Integer.class), row.get(2, LocalDate.class)))
                .toList();
    }

    private DeadlineExpressions deadlineExpressions(HibernateCriteriaBuilder builder, CriteriaQuery<?> query,
            Root<Announcement> announcement, AnnouncementSearchCondition condition) {
        LocalDate today = condition.today();
        Expression<LocalDate> confirmedEnd = minimumScheduleDate(builder, query, announcement, condition,
                ApplicationScheduleState.CONFIRMED, false);
        Expression<LocalDate> currentEnd = minimumScheduleDate(builder, query, announcement, condition, null, false);
        Expression<LocalDate> futureStart = minimumScheduleDate(builder, query, announcement, condition, null, true);
        Predicate reviewed = builder.isTrue(announcement.get("applicationScheduleReviewed"));
        Predicate upcoming = builder.greaterThan(announcement.get("applicationStartDate"), today);
        Predicate active = builder.greaterThanOrEqualTo(announcement.get("applicationEndDate"), today);
        Expression<Integer> reviewedPriority = builder.<Integer>selectCase()
                .when(builder.isNotNull(confirmedEnd), 0).when(builder.isNotNull(currentEnd), 1)
                .when(builder.isNotNull(futureStart), 2).otherwise(3);
        Expression<Integer> legacyPriority = builder.<Integer>selectCase()
                .when(upcoming, 2).when(active, 0).otherwise(3);
        Expression<LocalDate> reviewedDate = builder.<LocalDate>coalesce()
                .value(confirmedEnd).value(currentEnd).value(futureStart).value(today);
        Expression<LocalDate> legacyDate = builder.<LocalDate>selectCase()
                .when(upcoming, announcement.<LocalDate>get("applicationStartDate"))
                .when(active, announcement.<LocalDate>get("applicationEndDate")).otherwise(today);
        return new DeadlineExpressions(builder.<Integer>selectCase().when(reviewed, reviewedPriority)
                .otherwise(legacyPriority), builder.<LocalDate>selectCase().when(reviewed, reviewedDate)
                .otherwise(legacyDate));
    }

    private Expression<LocalDate> minimumScheduleDate(HibernateCriteriaBuilder builder, CriteriaQuery<?> query,
            Root<Announcement> announcement, AnnouncementSearchCondition condition,
            ApplicationScheduleState state, boolean future) {
        Subquery<LocalDate> subquery = query.subquery(LocalDate.class);
        Root<AnnouncementApplicationSchedule> schedule = subquery.from(AnnouncementApplicationSchedule.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(schedule.get("announcement"), announcement));
        String field = "endDate";
        if (future) {
            field = "startDate";
            predicates.add(builder.greaterThan(schedule.get("startDate"), condition.today()));
        }
        if (!future) {
            predicates.add(builder.lessThanOrEqualTo(schedule.get("startDate"), condition.today()));
            predicates.add(builder.greaterThanOrEqualTo(schedule.get("endDate"), condition.today()));
        }
        if (state != null) {
            predicates.add(builder.equal(schedule.get("state"), state));
        }
        if (hasValues(condition.regionCodes())) {
            Join<AnnouncementApplicationSchedule, HousingComplex> complex = schedule.join(
                    "housingComplex", jakarta.persistence.criteria.JoinType.LEFT);
            predicates.add(builder.or(builder.isNull(schedule.get("housingComplex")),
                    regionPredicate(builder, complex, condition.regionCodes())));
        }
        subquery.select(builder.least(schedule.<LocalDate>get(field))).where(predicates.toArray(Predicate[]::new));
        return subquery;
    }

    private record DeadlineExpressions(Expression<Integer> priority, Expression<LocalDate> date) {
    }

    /** 공고 한 건이 같은 조건의 공개 목록에 들어가는지. 목록 조회와 같은 조건식을 쓴다. */
    public boolean isListed(long announcementId, AnnouncementSearchCondition condition) {
        HibernateCriteriaBuilder criteriaBuilder = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = criteriaBuilder.createQuery(Long.class);
        Root<Announcement> announcement = query.from(Announcement.class);
        List<Predicate> predicates = searchPredicates(criteriaBuilder, query, announcement, condition);
        predicates.add(criteriaBuilder.equal(announcement.get("id"), announcementId));
        query.select(criteriaBuilder.count(announcement)).where(predicates.toArray(Predicate[]::new));
        return entityManager.createQuery(query).getSingleResult() > 0;
    }

    public long countLatestLeaves(AnnouncementSearchCondition condition) {
        HibernateCriteriaBuilder criteriaBuilder = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = criteriaBuilder.createQuery(Long.class);
        Root<Announcement> announcement = query.from(Announcement.class);
        List<Predicate> predicates = searchPredicates(criteriaBuilder, query, announcement, condition);
        query.select(criteriaBuilder.count(announcement)).where(predicates.toArray(Predicate[]::new));
        return entityManager.createQuery(query).getSingleResult();
    }

    private List<Predicate> searchPredicates(
            HibernateCriteriaBuilder criteriaBuilder,
            CriteriaQuery<?> query,
            Root<Announcement> announcement,
            AnnouncementSearchCondition condition
    ) {
        List<Predicate> predicates = new ArrayList<>();
        addVisibilityPredicates(criteriaBuilder, query, announcement, predicates);
        addDirectFilterPredicates(criteriaBuilder, announcement, condition, predicates);
        addDerivedFilterPredicates(criteriaBuilder, query, announcement, condition, predicates);
        return predicates;
    }

    private void addVisibilityPredicates(
            HibernateCriteriaBuilder criteriaBuilder,
            CriteriaQuery<?> query,
            Root<Announcement> announcement,
            List<Predicate> predicates
    ) {
        predicates.add(criteriaBuilder.isFalse(announcement.get("adminDeleted")));
        predicates.add(criteriaBuilder.equal(announcement.get("releaseStatus"), AnnouncementReleaseStatus.PUBLISHED));
        predicates.add(storedValueIn(
                criteriaBuilder,
                announcement.get("status"),
                Set.of(AnnouncementPublicationType.ORIGINAL, AnnouncementPublicationType.CORRECTION)
        ));
        predicates.add(criteriaBuilder.or(
                storedValueIn(
                        criteriaBuilder,
                        announcement.get("status"),
                        Set.of(AnnouncementPublicationType.ORIGINAL)
                ),
                criteriaBuilder.isNotNull(announcement.get("previousAnnouncement"))
        ));

        Subquery<Long> successorQuery = query.subquery(Long.class);
        Root<Announcement> successor = successorQuery.from(Announcement.class);
        successorQuery.select(successor.get("id"))
                .where(criteriaBuilder.equal(successor.get("previousAnnouncement"), announcement),
                        criteriaBuilder.isFalse(successor.get("adminDeleted")),
                        criteriaBuilder.equal(successor.get("releaseStatus"), AnnouncementReleaseStatus.PUBLISHED));
        predicates.add(criteriaBuilder.not(criteriaBuilder.exists(successorQuery)));
    }

    private void addDirectFilterPredicates(
            HibernateCriteriaBuilder criteriaBuilder,
            Root<Announcement> announcement,
            AnnouncementSearchCondition condition,
            List<Predicate> predicates
    ) {
        if (condition.keyword() != null) {
            predicates.add(criteriaBuilder.like(
                    criteriaBuilder.lower(announcement.get("name")),
                    escapedLikePattern(condition.keyword()),
                    '\\'
            ));
        }
        if (hasValues(condition.rentalTypes())) {
            predicates.add(storedValueIn(criteriaBuilder, announcement.get("supplyType"), condition.rentalTypes()));
        }
        if (hasValues(condition.publicationTypes())) {
            predicates.add(storedValueIn(criteriaBuilder, announcement.get("status"), condition.publicationTypes()));
        }
        if (hasValues(condition.agencyCodes())) {
            predicates.add(storedValueIn(criteriaBuilder, announcement.get("provider"), condition.agencyCodes()));
        }
        if (hasValues(condition.recruitmentTypes())) {
            predicates.add(storedValueIn(
                    criteriaBuilder,
                    announcement.get("recruitmentType"),
                    condition.recruitmentTypes()
            ));
        }
    }

    private void addCursorPredicate(
            HibernateCriteriaBuilder criteriaBuilder,
            Root<Announcement> announcement,
            LocalDate cursorPostedDate,
            Long cursorId,
            List<Predicate> predicates,
            boolean ascending
    ) {
        if (cursorPostedDate != null && cursorId != null) {
            predicates.add(criteriaBuilder.or(
                    afterCursor(criteriaBuilder, announcement.get("postedDate"), cursorPostedDate, ascending),
                    criteriaBuilder.and(
                            criteriaBuilder.equal(announcement.get("postedDate"), cursorPostedDate),
                            afterCursor(criteriaBuilder, announcement.get("id"), cursorId, ascending)
                    )
            ));
        }
    }

    private Order sortOrder(HibernateCriteriaBuilder builder, Expression<?> expression, boolean ascending) {
        if (ascending) {
            return builder.asc(expression);
        }
        return builder.desc(expression);
    }

    private <T extends Comparable<? super T>> Predicate afterCursor(HibernateCriteriaBuilder builder,
            Expression<T> expression, T value, boolean ascending) {
        if (ascending) {
            return builder.greaterThan(expression, value);
        }
        return builder.lessThan(expression, value);
    }

    private void addDerivedFilterPredicates(
            HibernateCriteriaBuilder criteriaBuilder,
            CriteriaQuery<?> query,
            Root<Announcement> announcement,
            AnnouncementSearchCondition condition,
            List<Predicate> predicates
    ) {
        addApplicationStatusPredicate(criteriaBuilder, query, announcement, condition, predicates);
        addApplicationPeriodPredicates(criteriaBuilder, query, announcement, condition, predicates);
        addRegionPredicate(criteriaBuilder, query, announcement, condition, predicates);
    }

    private void addApplicationStatusPredicate(
            HibernateCriteriaBuilder criteriaBuilder,
            CriteriaQuery<?> query,
            Root<Announcement> announcement,
            AnnouncementSearchCondition condition,
            List<Predicate> predicates
    ) {
        if (!hasValues(condition.applicationStatuses()) || condition.today() == null) {
            return;
        }

        List<Predicate> statusPredicates = condition.applicationStatuses().stream()
                .filter(applicationStatus -> applicationStatus != ApplicationStatus.CANCELLED)
                .map(applicationStatus -> applicationStatusPredicate(
                        criteriaBuilder,
                        query,
                        announcement,
                        condition,
                        applicationStatus,
                        condition.today()
                ))
                .toList();
        if (!statusPredicates.isEmpty()) {
            predicates.add(criteriaBuilder.or(statusPredicates.toArray(Predicate[]::new)));
        }
    }

    private Predicate applicationStatusPredicate(
            HibernateCriteriaBuilder criteriaBuilder,
            CriteriaQuery<?> query,
            Root<Announcement> announcement,
            AnnouncementSearchCondition condition,
            ApplicationStatus applicationStatus,
            LocalDate today
    ) {
        Predicate legacy = switch (applicationStatus) {
            case BEFORE_APPLICATION -> criteriaBuilder.greaterThan(
                    announcement.get("applicationStartDate"),
                    today
            );
            case APPLYING -> criteriaBuilder.and(
                    criteriaBuilder.lessThanOrEqualTo(announcement.get("applicationStartDate"), today),
                    criteriaBuilder.greaterThanOrEqualTo(announcement.get("applicationEndDate"), today)
            );
            case CLOSED -> criteriaBuilder.lessThan(announcement.get("applicationEndDate"), today);
            case CANCELLED, CONDITIONAL -> criteriaBuilder.disjunction();
        };
        Predicate confirmed = scheduleExists(criteriaBuilder, query, announcement, condition,
                ApplicationScheduleState.CONFIRMED, today, today, false);
        Predicate current = scheduleExists(criteriaBuilder, query, announcement, condition,
                null, today, today, false);
        Predicate future = scheduleExists(criteriaBuilder, query, announcement, condition,
                null, today.plusDays(1), null, true);
        Predicate reviewed = switch (applicationStatus) {
            case APPLYING -> confirmed;
            case CONDITIONAL -> criteriaBuilder.and(criteriaBuilder.not(confirmed), current);
            case BEFORE_APPLICATION -> criteriaBuilder.and(criteriaBuilder.not(current), future);
            case CLOSED -> criteriaBuilder.and(criteriaBuilder.not(current), criteriaBuilder.not(future));
            case CANCELLED -> criteriaBuilder.disjunction();
        };
        Predicate status = criteriaBuilder.or(
                criteriaBuilder.and(criteriaBuilder.isFalse(announcement.get("applicationScheduleReviewed")), legacy),
                criteriaBuilder.and(criteriaBuilder.isTrue(announcement.get("applicationScheduleReviewed")), reviewed)
        );
        if (applicationStatus != ApplicationStatus.CLOSED) {
            return status;
        }
        return criteriaBuilder.and(status, followUpOpen(criteriaBuilder, query, announcement, today));
    }

    // 접수 종료일, 당첨자 발표일, 공고 일정 종료일 중 하나라도 기준일 이후면 마지막 일정이 아직 남아 있다.
    // 확인된 접수 일정의 마지막 종료일은 공고 접수 종료일에 반영되어 있다.
    private Predicate followUpOpen(HibernateCriteriaBuilder builder, CriteriaQuery<?> query,
            Root<Announcement> announcement, LocalDate today) {
        LocalDate cutoff = ClosedAnnouncementFollowUp.cutoff(today);
        Subquery<Long> laterSchedule = query.subquery(Long.class);
        Root<AnnouncementSchedule> schedule = laterSchedule.from(AnnouncementSchedule.class);
        laterSchedule.select(schedule.get("id")).where(
                builder.equal(schedule.get("announcement"), announcement),
                builder.greaterThanOrEqualTo(schedule.get("endAt"), cutoff.atStartOfDay()));
        return builder.or(builder.greaterThanOrEqualTo(announcement.get("applicationEndDate"), cutoff),
                builder.greaterThanOrEqualTo(announcement.get("winnerAnnouncementDate"), cutoff),
                builder.exists(laterSchedule));
    }

    private void addApplicationPeriodPredicates(
            HibernateCriteriaBuilder criteriaBuilder,
            CriteriaQuery<?> query,
            Root<Announcement> announcement,
            AnnouncementSearchCondition condition,
            List<Predicate> predicates
    ) {
        if (condition.applicationFrom() == null && condition.applicationTo() == null) {
            return;
        }
        List<Predicate> legacy = new ArrayList<>();
        if (condition.applicationFrom() != null) {
            legacy.add(criteriaBuilder.greaterThanOrEqualTo(
                    announcement.get("applicationEndDate"), condition.applicationFrom()));
        }
        if (condition.applicationTo() != null) {
            legacy.add(criteriaBuilder.lessThanOrEqualTo(
                    announcement.get("applicationStartDate"), condition.applicationTo()));
        }
        predicates.add(criteriaBuilder.or(
                criteriaBuilder.and(criteriaBuilder.isFalse(announcement.get("applicationScheduleReviewed")),
                        criteriaBuilder.and(legacy.toArray(Predicate[]::new))),
                criteriaBuilder.and(criteriaBuilder.isTrue(announcement.get("applicationScheduleReviewed")),
                        scheduleExists(criteriaBuilder, query, announcement, condition, null,
                                condition.applicationFrom(), condition.applicationTo(), false))
        ));
    }

    private Predicate scheduleExists(
            HibernateCriteriaBuilder builder, CriteriaQuery<?> query,
            Root<Announcement> announcement, AnnouncementSearchCondition condition,
            ApplicationScheduleState state, LocalDate from, LocalDate to, boolean startsAfter
    ) {
        Subquery<Long> scheduleQuery = query.subquery(Long.class);
        Root<AnnouncementApplicationSchedule> schedule = scheduleQuery.from(AnnouncementApplicationSchedule.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(schedule.get("announcement"), announcement));
        if (state != null) {
            predicates.add(builder.equal(schedule.get("state"), state));
        }
        if (from != null) {
            String field = "endDate";
            if (startsAfter) {
                field = "startDate";
            }
            predicates.add(builder.greaterThanOrEqualTo(schedule.get(field), from));
        }
        if (to != null) {
            predicates.add(builder.lessThanOrEqualTo(schedule.get("startDate"), to));
        }
        if (hasValues(condition.regionCodes())) {
            Join<AnnouncementApplicationSchedule, HousingComplex> complex = schedule.join(
                    "housingComplex", jakarta.persistence.criteria.JoinType.LEFT);
            predicates.add(builder.or(builder.isNull(schedule.get("housingComplex")),
                    regionPredicate(builder, complex, condition.regionCodes())));
        }
        scheduleQuery.select(schedule.get("id")).where(predicates.toArray(Predicate[]::new));
        return builder.exists(scheduleQuery);
    }

    private void addRegionPredicate(
            HibernateCriteriaBuilder criteriaBuilder,
            CriteriaQuery<?> query,
            Root<Announcement> announcement,
            AnnouncementSearchCondition condition,
            List<Predicate> predicates
    ) {
        if (!hasValues(condition.regionCodes())) {
            return;
        }

        Subquery<Long> linkQuery = query.subquery(Long.class);
        Root<AnnouncementHousingComplex> link = linkQuery.from(AnnouncementHousingComplex.class);
        Join<AnnouncementHousingComplex, HousingComplex> housingComplex = link.join("housingComplex");
        linkQuery.select(link.get("housingComplexId"))
                .where(
                        criteriaBuilder.equal(link.get("announcement"), announcement),
                        regionPredicate(criteriaBuilder, housingComplex, condition.regionCodes())
                );
        predicates.add(criteriaBuilder.exists(linkQuery));
    }

    private Predicate regionPredicate(HibernateCriteriaBuilder builder, Path<HousingComplex> complex,
            Set<String> codes) {
        if (codes.stream().anyMatch(code -> code.length() == 10)) {
            var prefixes = codes.stream().map(code -> code.substring(0, 8)).toList();
            return builder.substring(complex.get("address").get("legalDongCode"), 1, 8).in(prefixes);
        }
        return complex.get("address").get("cityCountyDistrictCode").in(codes);
    }

    private boolean hasValues(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    private String escapedLikePattern(String keyword) {
        String escapedKeyword = keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escapedKeyword.toLowerCase(Locale.ROOT) + "%";
    }

    @SuppressWarnings("unchecked")
    private <T extends Enum<T> & LegacyStoredValue> Predicate storedValueIn(
            HibernateCriteriaBuilder criteriaBuilder,
            Path<T> path,
            Set<T> values
    ) {
        Set<String> storedValues = values.stream()
                .flatMap(value -> value.storedValues().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        JpaExpression<T> expression = (JpaExpression<T>) path;
        return criteriaBuilder.cast(expression, String.class).in(storedValues);
    }

    public record AdminCounts(long upcoming, long applying, long closed, long conditional, long cancelled,
            long unknown, long attention, long scheduleMissing, long supplyMissing, long unconfirmed) {
    }
}
