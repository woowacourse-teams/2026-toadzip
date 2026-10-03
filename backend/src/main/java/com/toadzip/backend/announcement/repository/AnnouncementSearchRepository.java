package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.global.persistence.LegacyStoredValue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
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
        HibernateCriteriaBuilder criteriaBuilder = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();
        CriteriaQuery<Announcement> query = criteriaBuilder.createQuery(Announcement.class);
        Root<Announcement> announcement = query.from(Announcement.class);
        List<Predicate> predicates = new ArrayList<>();

        addVisibilityPredicates(criteriaBuilder, query, announcement, predicates);
        addDirectFilterPredicates(criteriaBuilder, announcement, condition, predicates);
        addDerivedFilterPredicates(criteriaBuilder, query, announcement, condition, predicates);
        addCursorPredicate(criteriaBuilder, announcement, cursorPostedDate, cursorId, predicates);

        query.select(announcement)
                .where(predicates.toArray(Predicate[]::new))
                .orderBy(
                        criteriaBuilder.desc(announcement.get("postedDate")),
                        criteriaBuilder.desc(announcement.get("id"))
                );

        return entityManager.createQuery(query)
                .setMaxResults(limit)
                .getResultList();
    }

    public long countLatestLeaves(AnnouncementSearchCondition condition) {
        HibernateCriteriaBuilder builder = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<Announcement> announcement = query.from(Announcement.class);
        List<Predicate> predicates = new ArrayList<>();
        addVisibilityPredicates(builder, query, announcement, predicates);
        addDirectFilterPredicates(builder, announcement, condition, predicates);
        addDerivedFilterPredicates(builder, query, announcement, condition, predicates);
        query.select(builder.countDistinct(announcement.get("id")))
                .where(predicates.toArray(Predicate[]::new));
        return entityManager.createQuery(query).getSingleResult();
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
            List<Predicate> predicates
    ) {
        if (cursorPostedDate != null && cursorId != null) {
            predicates.add(criteriaBuilder.or(
                    criteriaBuilder.lessThan(announcement.get("postedDate"), cursorPostedDate),
                    criteriaBuilder.and(
                            criteriaBuilder.equal(announcement.get("postedDate"), cursorPostedDate),
                            criteriaBuilder.lessThan(announcement.get("id"), cursorId)
                    )
            ));
        }
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
        addSpatialPredicate(criteriaBuilder, query, announcement, condition, predicates);
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
        if (hasSpatialCondition(condition)) {
            Join<AnnouncementApplicationSchedule, HousingComplex> complex = schedule.join(
                    "housingComplex", jakarta.persistence.criteria.JoinType.LEFT);
            Predicate spatial = spatialComplexPredicate(builder, complex, condition);
            if (condition.scope() != null) {
                spatial = builder.and(spatial, connectedComplexPredicate(builder, scheduleQuery, announcement, complex));
            }
            predicates.add(builder.or(builder.isNull(schedule.get("housingComplex")), spatial));
        }
        scheduleQuery.select(schedule.get("id")).where(predicates.toArray(Predicate[]::new));
        return builder.exists(scheduleQuery);
    }

    private void addSpatialPredicate(
            HibernateCriteriaBuilder builder,
            CriteriaQuery<?> query,
            Root<Announcement> announcement,
            AnnouncementSearchCondition condition,
            List<Predicate> predicates
    ) {
        if (!hasSpatialCondition(condition)) {
            return;
        }
        Subquery<Long> supplyRowQuery = query.subquery(Long.class);
        Root<SupplyRow> supplyRow = supplyRowQuery.from(SupplyRow.class);
        Join<SupplyRow, HousingComplex> complex = supplyRow.join("housingComplex");
        supplyRowQuery.select(supplyRow.get("id"))
                .where(builder.equal(supplyRow.get("announcement"), announcement),
                        spatialComplexPredicate(builder, complex, condition));
        predicates.add(builder.exists(supplyRowQuery));
    }

    private Predicate spatialComplexPredicate(
            HibernateCriteriaBuilder builder, Path<HousingComplex> complex, AnnouncementSearchCondition condition
    ) {
        List<Predicate> predicates = new ArrayList<>();
        if (condition.scope() != null) {
            predicates.add(builder.isFalse(complex.get("adminDeleted")));
        }
        if (hasValues(condition.regionCodes())) {
            predicates.add(complex.get("address").get("cityCountyDistrictCode").in(condition.regionCodes()));
        }
        MapBounds bounds = condition.bounds();
        if (bounds != null) {
            predicates.add(builder.between(complex.get("address").get("latitude"),
                    bounds.southWestLat(), bounds.northEastLat()));
            predicates.add(builder.between(complex.get("address").get("longitude"),
                    bounds.southWestLng(), bounds.northEastLng()));
        }
        return builder.and(predicates.toArray(Predicate[]::new));
    }

    private Predicate connectedComplexPredicate(
            HibernateCriteriaBuilder builder, Subquery<?> query,
            Root<Announcement> announcement, Path<HousingComplex> complex
    ) {
        Subquery<Long> supplyRowQuery = query.subquery(Long.class);
        Root<SupplyRow> supplyRow = supplyRowQuery.from(SupplyRow.class);
        supplyRowQuery.select(supplyRow.get("id")).where(
                builder.equal(supplyRow.get("announcement"), announcement),
                builder.equal(supplyRow.get("housingComplex"), complex)
        );
        return builder.exists(supplyRowQuery);
    }

    private boolean hasSpatialCondition(AnnouncementSearchCondition condition) {
        return condition.scope() != null || condition.bounds() != null || hasValues(condition.regionCodes());
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
