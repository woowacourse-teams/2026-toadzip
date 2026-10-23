package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementSort;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.global.persistence.LegacyStoredValue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaExpression;
import org.springframework.stereotype.Repository;

@Repository
public class AnnouncementSearchRepository {

    private final EntityManager entityManager;

    public AnnouncementSearchRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
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
        boolean ascending = sort != AnnouncementSort.DEADLINE_DESC;
        boolean publicationAscending = sort == AnnouncementSort.LATEST_ASC || sort == AnnouncementSort.DEADLINE_DESC;
        HibernateCriteriaBuilder builder = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<Announcement> announcement = query.from(Announcement.class);
        List<Predicate> predicates = searchPredicates(builder, query, announcement, condition);
        DeadlineExpressions order = deadlineExpressions(builder, query, announcement, condition);
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
                    announcement.get("postedDate"), cursorPostedDate, !ascending),
                    builder.and(builder.equal(announcement.get("postedDate"), cursorPostedDate),
                            afterCursor(builder, announcement.get("id"), cursorId, !ascending)));
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
                        criteriaBuilder.isFalse(successor.get("adminDeleted")));
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
        return criteriaBuilder.or(
                criteriaBuilder.and(criteriaBuilder.isFalse(announcement.get("applicationScheduleReviewed")), legacy),
                criteriaBuilder.and(criteriaBuilder.isTrue(announcement.get("applicationScheduleReviewed")), reviewed)
        );
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

        Subquery<Long> supplyRowQuery = query.subquery(Long.class);
        Root<SupplyRow> supplyRow = supplyRowQuery.from(SupplyRow.class);
        Join<SupplyRow, HousingComplex> housingComplex = supplyRow.join("housingComplex");
        supplyRowQuery.select(supplyRow.get("id"))
                .where(
                        criteriaBuilder.equal(supplyRow.get("announcement"), announcement),
                        criteriaBuilder.isFalse(supplyRow.get("adminExcluded")),
                        criteriaBuilder.isNotNull(supplyRow.get("housingComplex")),
                        regionPredicate(criteriaBuilder, housingComplex, condition.regionCodes())
                );
        predicates.add(criteriaBuilder.exists(supplyRowQuery));
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
}
