package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.HousingComplex;
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

    private void addVisibilityPredicates(
            HibernateCriteriaBuilder criteriaBuilder,
            CriteriaQuery<Announcement> query,
            Root<Announcement> announcement,
            List<Predicate> predicates
    ) {
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
                .where(criteriaBuilder.equal(successor.get("previousAnnouncement"), announcement));
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
            CriteriaQuery<Announcement> query,
            Root<Announcement> announcement,
            AnnouncementSearchCondition condition,
            List<Predicate> predicates
    ) {
        addApplicationStatusPredicate(criteriaBuilder, announcement, condition, predicates);
        addApplicationPeriodPredicates(criteriaBuilder, announcement, condition, predicates);
        addRegionPredicate(criteriaBuilder, query, announcement, condition, predicates);
    }

    private void addApplicationStatusPredicate(
            HibernateCriteriaBuilder criteriaBuilder,
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
                        announcement,
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
            Root<Announcement> announcement,
            ApplicationStatus applicationStatus,
            LocalDate today
    ) {
        return switch (applicationStatus) {
            case BEFORE_APPLICATION -> criteriaBuilder.greaterThan(
                    announcement.get("applicationStartDate"),
                    today
            );
            case APPLYING -> criteriaBuilder.and(
                    criteriaBuilder.lessThanOrEqualTo(announcement.get("applicationStartDate"), today),
                    criteriaBuilder.greaterThanOrEqualTo(announcement.get("applicationEndDate"), today)
            );
            case CLOSED -> criteriaBuilder.lessThan(announcement.get("applicationEndDate"), today);
            case CANCELLED -> criteriaBuilder.disjunction();
        };
    }

    private void addApplicationPeriodPredicates(
            HibernateCriteriaBuilder criteriaBuilder,
            Root<Announcement> announcement,
            AnnouncementSearchCondition condition,
            List<Predicate> predicates
    ) {
        if (condition.applicationFrom() != null) {
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                    announcement.get("applicationEndDate"),
                    condition.applicationFrom()
            ));
        }
        if (condition.applicationTo() != null) {
            predicates.add(criteriaBuilder.lessThanOrEqualTo(
                    announcement.get("applicationStartDate"),
                    condition.applicationTo()
            ));
        }
    }

    private void addRegionPredicate(
            HibernateCriteriaBuilder criteriaBuilder,
            CriteriaQuery<Announcement> query,
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
                        criteriaBuilder.isNotNull(supplyRow.get("housingComplex")),
                        housingComplex.get("address").get("cityCountyDistrictCode").in(condition.regionCodes())
                );
        predicates.add(criteriaBuilder.exists(supplyRowQuery));
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
