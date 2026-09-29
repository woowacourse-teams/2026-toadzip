package com.toadzip.backend.ingest.failure.domain;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;
import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailureReason;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IngestFailureTest {

    private static final Instant FIRST_AT = Instant.parse("2026-09-29T00:00:00Z");
    private static final Instant OBSERVED_AT = FIRST_AT.plusSeconds(60);
    private static final Instant RESOLVED_AT = FIRST_AT.plusSeconds(120);

    @ParameterizedTest
    @MethodSource("failures")
    <T extends IngestFailure<T>> void 반복과_재발을_구분하고_최초_발생과_해결_이력을_보존한다(
            T failure, T observed, String[] specificFields, Object[] expectedValues
    ) {
        UUID firstExecution = UUID.randomUUID();
        UUID repeatedExecution = UUID.randomUUID();
        UUID resolvedExecution = UUID.randomUUID();
        String sourceKey = failure.getSourceKey();
        Enum<?> reason = failure.getReason();
        failure.attachFirstExecution(firstExecution);

        failure.observe(observed, repeatedExecution);

        assertThat(failure).extracting("status", "occurrenceCount", "recurrenceCount", "lastExecutionId")
                .containsExactly(PENDING, 2, 0, repeatedExecution);
        assertThat(failure).extracting(specificFields).containsExactly(expectedValues);
        failure.resolve(RESOLVED_AT, resolvedExecution);
        failure.resolve(RESOLVED_AT.plusSeconds(30), UUID.randomUUID());
        assertThat(failure.getStatus()).isEqualTo(RESOLVED);
        failure.observe(observed, null);

        assertThat(failure).extracting("occurredAt", "firstExecutionId", "lastResolvedAt", "lastResolvedExecutionId")
                .containsExactly(FIRST_AT, firstExecution, RESOLVED_AT, resolvedExecution);
        assertThat(failure).extracting("status", "occurrenceCount", "recurrenceCount", "lastExecutionId")
                .containsExactly(PENDING, 3, 1, null);
        assertThat(failure).extracting("detail", "lastOccurredAt").containsExactly("새 실패", OBSERVED_AT);
        assertThat(failure.getSourceKey()).isEqualTo(sourceKey);
        assertThat(failure.getReason()).isEqualTo(reason);
    }

    @ParameterizedTest
    @MethodSource("failures")
    <T extends IngestFailure<T>> void 잘못된_관찰과_해결_시각은_이미_해결한_실패에서도_거절한다(
            T failure, T observed, String[] specificFields, Object[] expectedValues
    ) {
        assertThatThrownBy(() -> failure.observe(null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("관찰한 실패은 필수입니다.");
        failure.resolve(RESOLVED_AT, null);
        assertThatThrownBy(() -> failure.resolve(null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("해결 시각은 필수입니다.");
        assertThat(failure.getStatus()).isEqualTo(RESOLVED);
    }

    private static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(
                        MyHomeComplexMappingFailure.create("source", "old",
                                MyHomeComplexMappingFailureReason.INVALID_VALUE, "첫 실패", FIRST_AT),
                        MyHomeComplexMappingFailure.create("other-source", "new",
                                MyHomeComplexMappingFailureReason.GEOCODING_ERROR, "새 실패", OBSERVED_AT),
                        new String[]{"sourceComplexIdentifier"}, new Object[]{"new"}
                ),
                Arguments.of(
                        MyHomeAnnouncementMappingFailure.create("source", "old", 1,
                                MyHomeAnnouncementMappingFailureReason.INVALID_VALUE, "첫 실패", FIRST_AT),
                        MyHomeAnnouncementMappingFailure.create("other-source", "new", 2,
                                MyHomeAnnouncementMappingFailureReason.COMPLEX_NOT_FOUND, "새 실패", OBSERVED_AT),
                        new String[]{"sourceAnnouncementIdentifier", "sourceHouseSerialNumber"}, new Object[]{"new", 2}
                ),
                Arguments.of(
                        LhAnnouncementEnrichmentFailure.create("source", "old", "old-pan",
                                LhAnnouncementEnrichmentFailureReason.INVALID_VALUE, "첫 실패", FIRST_AT),
                        LhAnnouncementEnrichmentFailure.create("other-source", "new", "new-pan",
                                LhAnnouncementEnrichmentFailureReason.ANNOUNCEMENT_NOT_FOUND, "새 실패", OBSERVED_AT),
                        new String[]{"sourceAnnouncementIdentifier", "panId"}, new Object[]{"new", "new-pan"}
                ),
                Arguments.of(
                        LhHouseholdEnrichmentFailure.create("source", "서울", "국민임대", "old",
                                LhHouseholdEnrichmentFailureReason.INVALID_SOURCE, "첫 실패", FIRST_AT),
                        LhHouseholdEnrichmentFailure.create("other-source", "부산", "행복주택", "new",
                                LhHouseholdEnrichmentFailureReason.INVALID_SOURCE, "새 실패", OBSERVED_AT),
                        new String[]{"areaName", "supplyTypeName", "complexName"}, new Object[]{"부산", "행복주택", "new"}
                )
        );
    }
}
