package com.toadzip.backend.ingest.failure.repository;

import static com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason.ANNOUNCEMENT_NOT_FOUND;
import static com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailureReason.INVALID_SOURCE;
import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;
import static com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason.COMPLEX_NOT_FOUND;
import static com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason.INVALID_VALUE;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureStore;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureStore;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureStore;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestFailureStoreQueryScopeTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);

    @Test
    void 마이홈_공고_실패_조정은_현재_실패와_이번_실행의_원천만_조회한다() {
        MyHomeAnnouncementMappingFailureRepository repository = mock(
                MyHomeAnnouncementMappingFailureRepository.class
        );
        when(repository.findAllByStatus(PENDING)).thenReturn(List.of());
        when(repository.findAllBySourceKeyIn(anyCollection())).thenReturn(List.of());
        var store = new MyHomeAnnouncementMappingFailureStore(repository, CLOCK);

        store.replaceAll(List.of(MyHomeAnnouncementMappingFailure.create(
                "source-key", "announcement-id", 1, COMPLEX_NOT_FOUND, "실패", OCCURRED_AT
        )), null);

        verify(repository).findAllByStatus(PENDING);
        verify(repository).findAllBySourceKeyIn(List.of("source-key"));
        verify(repository, never()).findAll();
    }

    @Test
    void LH_공고_실패_조정은_현재_실패와_이번_실행의_원천만_조회한다() {
        LhAnnouncementEnrichmentFailureRepository repository = mock(
                LhAnnouncementEnrichmentFailureRepository.class
        );
        when(repository.findAllByStatus(PENDING)).thenReturn(List.of());
        when(repository.findAllBySourceKeyIn(anyCollection())).thenReturn(List.of());
        var store = new LhAnnouncementEnrichmentFailureStore(repository, CLOCK);

        store.replaceAll(List.of(LhAnnouncementEnrichmentFailure.create(
                "source-key", "announcement-id", "pan-id", ANNOUNCEMENT_NOT_FOUND,
                "실패", OCCURRED_AT
        )), null);

        verify(repository).findAllByStatus(PENDING);
        verify(repository).findAllBySourceKeyIn(List.of("source-key"));
        verify(repository, never()).findAll();
    }

    @Test
    void LH_세대수_실패_조정은_현재_실패와_이번_실행의_원천만_조회한다() {
        LhHouseholdEnrichmentFailureRepository repository = mock(
                LhHouseholdEnrichmentFailureRepository.class
        );
        when(repository.findAllByStatus(PENDING)).thenReturn(List.of());
        when(repository.findAllBySourceKeyIn(anyCollection())).thenReturn(List.of());
        var store = new LhHouseholdEnrichmentFailureStore(repository, CLOCK);

        store.replaceAll(List.of(LhHouseholdEnrichmentFailure.create(
                "source-key", "서울", "국민임대", "단지", INVALID_SOURCE, "실패", OCCURRED_AT
        )), null);

        verify(repository).findAllByStatus(PENDING);
        verify(repository).findAllBySourceKeyIn(List.of("source-key"));
        verify(repository, never()).findAll();
    }

    @Test
    void 마이홈_단지_준비_실패_조정은_준비_사유와_이번_실행의_원천만_조회한다() {
        MyHomeComplexMappingFailureRepository repository = mock(
                MyHomeComplexMappingFailureRepository.class
        );
        when(repository.findAllByReasonInAndStatus(anyCollection(), eq(PENDING)))
                .thenReturn(List.of());
        when(repository.findAllByReasonInAndSourceKeyIn(anyCollection(), anyCollection()))
                .thenReturn(List.of());
        var store = new MyHomeComplexMappingFailureStore(repository, CLOCK);

        store.replacePreparationFailures(List.of(MyHomeComplexMappingFailure.create(
                "source-key", "complex-id", INVALID_VALUE, "실패", OCCURRED_AT
        )), null);

        verify(repository).findAllByReasonInAndStatus(anyCollection(), eq(PENDING));
        verify(repository).findAllByReasonInAndSourceKeyIn(
                anyCollection(), eq(List.of("source-key"))
        );
        verify(repository, never()).findAll();
    }
}
