package com.toadzip.backend.ingest.failure.repository;

import static com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason.ANNOUNCEMENT_NOT_FOUND;
import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;
import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.RESOLVED;
import static com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason.COMPLEX_NOT_FOUND;
import static com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason.GEOCODING_ERROR;
import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.collection.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.collection.repository.ExternalDataFailureStore;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureStore;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureRepository;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureStore;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class IngestFailureLifecycleIntegrationTest {

    @Autowired
    private MyHomeAnnouncementMappingFailureStore announcementStore;

    @Autowired
    private MyHomeAnnouncementMappingFailureRepository announcementRepository;

    @Autowired
    private MyHomeComplexMappingFailureStore complexStore;

    @Autowired
    private MyHomeComplexMappingFailureRepository complexRepository;

    @Autowired
    private LhAnnouncementEnrichmentFailureStore enrichmentStore;

    @Autowired
    private LhAnnouncementEnrichmentFailureRepository enrichmentRepository;

    @Autowired
    private ExternalDataFailureStore externalFailureStore;

    @Autowired
    private ExternalDataCollectionFailureRepository externalFailureRepository;

    @Autowired
    private LhHouseholdEnrichmentFailureRepository householdFailureRepository;

    @BeforeEach
    void setUp() {
        externalFailureRepository.deleteAllInBatch();
        householdFailureRepository.deleteAllInBatch();
        enrichmentRepository.deleteAllInBatch();
        announcementRepository.deleteAllInBatch();
        complexRepository.deleteAllInBatch();
    }

    @Test
    void 외부_수집_실패도_반복과_재발을_한_이력으로_연결한다() {
        UUID firstExecutionId = UUID.randomUUID();
        UUID resolvedExecutionId = UUID.randomUUID();
        UUID recurredExecutionId = UUID.randomUUID();
        Instant firstOccurredAt = Instant.parse("2026-09-19T00:00:00Z");
        Instant secondOccurredAt = Instant.parse("2026-09-19T01:00:00Z");

        externalFailureStore.store(externalFailure(
                firstOccurredAt,
                "첫 실패"
        ), firstExecutionId);
        externalFailureStore.resolve(
                ExternalDataSource.MYHOME_COMPLEX,
                "page=1",
                Instant.parse("2026-09-19T00:30:00Z"),
                resolvedExecutionId
        );
        externalFailureStore.store(externalFailure(
                secondOccurredAt,
                "재발"
        ), recurredExecutionId);

        assertThat(externalFailureRepository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.PENDING);
            assertThat(failure.getOccurredAt()).isEqualTo(firstOccurredAt);
            assertThat(failure.getLastOccurredAt()).isEqualTo(secondOccurredAt);
            assertThat(failure.getOccurrenceCount()).isEqualTo(2);
            assertThat(failure.getRecurrenceCount()).isOne();
            assertThat(failure.getFirstExecutionId()).isEqualTo(firstExecutionId);
            assertThat(failure.getLastExecutionId()).isEqualTo(recurredExecutionId);
            assertThat(failure.getResolvedExecutionId()).isEqualTo(resolvedExecutionId);
        });
    }

    @Test
    void 외부_수집의_현재_실패는_요청별_최신_상태로_판단한다() {
        ExternalDataCollectionFailure oldPending = externalFailure(
                Instant.parse("2026-09-19T00:00:00Z"),
                "과거 실패"
        );
        ExternalDataCollectionFailure latestResolved = externalFailure(
                Instant.parse("2026-09-19T01:00:00Z"),
                "최근 해결된 실패"
        );
        latestResolved.resolve(Instant.parse("2026-09-19T02:00:00Z"), null);
        externalFailureRepository.saveAllAndFlush(List.of(oldPending, latestResolved));

        assertThat(externalFailureRepository.findLatestPendingByRequest(PageRequest.of(0, 100)))
                .isEmpty();
    }

    @Test
    void 같은_공고_정제_실패의_반복_해결_재발과_실행_ID를_보존한다() {
        UUID firstExecutionId = UUID.randomUUID();
        UUID repeatedExecutionId = UUID.randomUUID();
        UUID resolvedExecutionId = UUID.randomUUID();
        UUID recurredExecutionId = UUID.randomUUID();

        announcementStore.replaceAll(List.of(
                announcementFailure("처음 실패", "2026-09-19T00:00:00Z")
        ), firstExecutionId);
        announcementStore.replaceAll(List.of(
                announcementFailure("같은 실패 반복", "2026-09-19T01:00:00Z")
        ), repeatedExecutionId);
        announcementStore.replaceAll(List.of(), resolvedExecutionId);
        announcementStore.replaceAll(List.of(
                announcementFailure("해결 후 재발", "2026-09-19T03:00:00Z")
        ), recurredExecutionId);

        assertThat(announcementRepository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getStatus()).isEqualTo(PENDING);
            assertThat(failure.getOccurredAt()).isEqualTo(Instant.parse("2026-09-19T00:00:00Z"));
            assertThat(failure.getLastOccurredAt()).isEqualTo(Instant.parse("2026-09-19T03:00:00Z"));
            assertThat(failure.getOccurrenceCount()).isEqualTo(3);
            assertThat(failure.getRecurrenceCount()).isOne();
            assertThat(failure.getLastResolvedAt()).isNotNull();
            assertThat(failure.getFirstExecutionId()).isEqualTo(firstExecutionId);
            assertThat(failure.getLastExecutionId()).isEqualTo(recurredExecutionId);
            assertThat(failure.getLastResolvedExecutionId()).isEqualTo(resolvedExecutionId);
            assertThat(failure.getDetail()).isEqualTo("해결 후 재발");
        });
    }

    @Test
    void 단지별_갱신은_해당_단지의_사라진_실패만_해결한다() {
        complexStore.replaceForComplex(
                "complex-a",
                List.of(complexFailure("source-a", "complex-a")),
                null
        );
        complexStore.replaceForComplex(
                "complex-b",
                List.of(complexFailure("source-b", "complex-b")),
                null
        );

        complexStore.replaceForComplex("complex-a", List.of(), null);

        assertThat(complexRepository.findAllBySourceComplexIdentifier("complex-a"))
                .singleElement()
                .extracting(MyHomeComplexMappingFailure::getStatus)
                .isEqualTo(RESOLVED);
        assertThat(complexRepository.findAllBySourceComplexIdentifier("complex-b"))
                .singleElement()
                .extracting(MyHomeComplexMappingFailure::getStatus)
                .isEqualTo(PENDING);
    }

    @Test
    void 후보_준비_동기화는_아직_재처리하지_않은_좌표_실패를_해결하지_않는다() {
        complexStore.replaceForComplex(
                "complex-a",
                List.of(complexFailure("source-a", "complex-a")),
                null
        );

        complexStore.replacePreparationFailures(List.of(), null);

        assertThat(complexRepository.findAll()).singleElement()
                .extracting(MyHomeComplexMappingFailure::getStatus)
                .isEqualTo(PENDING);
    }

    @Test
    void LH_보강_실패가_다음_실행에서_사라지면_이력을_삭제하지_않고_해결한다() {
        enrichmentStore.replaceAll(List.of(LhAnnouncementEnrichmentFailure.create(
                "source-key", "announcement-id", "pan-id", ANNOUNCEMENT_NOT_FOUND,
                "공고 없음", Instant.parse("2026-09-19T00:00:00Z")
        )), null);

        enrichmentStore.replaceAll(List.of(), null);

        assertThat(enrichmentRepository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getStatus()).isEqualTo(RESOLVED);
            assertThat(failure.getLastResolvedAt()).isNotNull();
        });
    }

    private MyHomeAnnouncementMappingFailure announcementFailure(
            String detail,
            String occurredAt
    ) {
        return MyHomeAnnouncementMappingFailure.create(
                "source-key",
                "announcement-id",
                1,
                COMPLEX_NOT_FOUND,
                detail,
                Instant.parse(occurredAt)
        );
    }

    private ExternalDataCollectionFailure externalFailure(Instant occurredAt, String reason) {
        return ExternalDataCollectionFailure.create(
                ExternalDataSource.MYHOME_COMPLEX,
                "page=1",
                occurredAt,
                1,
                "IOException",
                reason
        );
    }

    private MyHomeComplexMappingFailure complexFailure(
            String sourceKey,
            String sourceComplexIdentifier
    ) {
        return MyHomeComplexMappingFailure.create(
                sourceKey,
                sourceComplexIdentifier,
                GEOCODING_ERROR,
                "좌표 실패",
                Instant.parse("2026-09-19T00:00:00Z")
        );
    }

}
