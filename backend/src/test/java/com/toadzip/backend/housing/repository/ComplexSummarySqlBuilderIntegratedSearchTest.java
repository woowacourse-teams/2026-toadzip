package com.toadzip.backend.housing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.housing.domain.MapBounds;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ComplexSummarySqlBuilderIntegratedSearchTest {

    private final ComplexSummarySqlBuilder builder = new ComplexSummarySqlBuilder();

    @Test
    void 모집중_공고_조건을_지도와_목록에_동일하게_적용한다() {
        HousingComplexSearchCondition condition = condition(Set.of(), true);

        ComplexSummarySqlQuery query = builder.buildMapQuery(condition);

        assertThat(query.sql())
                .contains("representative.application_start_date <= :today")
                .contains("representative.application_end_date >= :today");
        assertThat(query.parameters())
                .containsEntry("today", LocalDate.of(2026, 9, 1));
    }

    @Test
    void 취소_상태는_최신_취소_공고가_연결된_단지만_조회한다() {
        ComplexSummarySqlQuery query = builder.buildMapQuery(
                condition(Set.of(ApplicationStatus.CANCELLED), null)
        );

        assertThat(query.sql())
                .contains("cancelled_announcement.status IN ('CANCELLATION', '취소공고')")
                .contains("cancelled_successor.previous_announcement_id = cancelled_announcement.id");
    }

    private HousingComplexSearchCondition condition(
            Set<ApplicationStatus> statuses,
            Boolean hasActiveAnnouncement
    ) {
        return new HousingComplexSearchCondition(
                MapBounds.of(
                        new BigDecimal("37.50"),
                        new BigDecimal("126.90"),
                        new BigDecimal("37.60"),
                        new BigDecimal("127.05")
                ),
                null,
                null,
                Set.of(),
                Set.of(),
                statuses,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                hasActiveAnnouncement,
                LocalDate.of(2026, 9, 1)
        );
    }
}
