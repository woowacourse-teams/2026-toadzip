package com.toadzip.backend.ingest.refinement.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;
import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.RESOLVED;
import static com.toadzip.backend.ingest.refinement.domain.AnnouncementRefinementFailureReason.SOURCE_LOOKUP_FAILED;
import static com.toadzip.backend.ingest.refinement.domain.AnnouncementRefinementFailureReason.SOURCE_REFINEMENT_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.toadzip.backend.global.config.ClockConfig;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.refinement.domain.AnnouncementRefinementFailure;
import com.toadzip.backend.ingest.refinement.domain.AnnouncementRefinementFailureReason;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AnnouncementRefinementFailureStore.class, ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class AnnouncementRefinementFailureStoreTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-16T00:00:00Z");
    private static final String LH_KEY = "01:06:10:2026000123";

    @Autowired
    private AnnouncementRefinementFailureStore store;

    @Autowired
    private AnnouncementRefinementFailureRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAllInBatch();
    }

    @Test
    void LH_미해결_실패가_있을_때_GH_실행이_성공해도_LH_실패는_그대로_남는다() {
        store.reconcile(AgencyCode.LH, Set.of(LH_KEY), List.of(failure(AgencyCode.LH, LH_KEY)), UUID.randomUUID());

        store.reconcile(AgencyCode.GH, Set.of("GH:2026-1"), List.of(), UUID.randomUUID());

        assertThat(repository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getProvider()).isEqualTo(AgencyCode.LH);
            assertThat(failure.getStatus()).isEqualTo(PENDING);
        });
    }

    @Test
    void 같은_기관이라도_이번에_다루지_않은_원천의_실패는_그대로_남고_다시_성공한_원천만_해결된다() {
        UUID resolvedBy = UUID.randomUUID();
        store.reconcile(AgencyCode.SH, Set.of("SH:1", "SH:2"),
                List.of(failure(AgencyCode.SH, "SH:1"), failure(AgencyCode.SH, "SH:2")), UUID.randomUUID());

        store.reconcile(AgencyCode.SH, Set.of("SH:1", "SH 페이지 3"), List.of(), resolvedBy);

        assertThat(repository.findAll()).extracting(failure -> failure.getSourceKey(), failure -> failure.getStatus(),
                        failure -> failure.getLastResolvedExecutionId())
                .containsExactlyInAnyOrder(tuple("SH:1", RESOLVED, resolvedBy), tuple("SH:2", PENDING, null));
    }

    @Test
    void 해결한_원천이_다시_실패하면_새로_만들지_않고_재발로_센다() {
        UUID first = UUID.randomUUID();
        UUID recurred = UUID.randomUUID();
        store.reconcile(AgencyCode.GH, Set.of("GH:7"), List.of(failure(AgencyCode.GH, "GH:7")), first);
        store.reconcile(AgencyCode.GH, Set.of("GH:7"), List.of(), UUID.randomUUID());

        store.reconcile(AgencyCode.GH, Set.of("GH:7"), List.of(failure(AgencyCode.GH, "GH:7")), recurred);

        assertThat(repository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getStatus()).isEqualTo(PENDING);
            assertThat(failure.getOccurrenceCount()).isEqualTo(2);
            assertThat(failure.getRecurrenceCount()).isOne();
            assertThat(failure.getFirstExecutionId()).isEqualTo(first);
            assertThat(failure.getLastExecutionId()).isEqualTo(recurred);
        });
    }

    @Test
    void 원천_정제_실패는_공고와_잇는_값을_남기고_묶음_읽기_실패는_남기지_않는다() {
        store.reconcile(AgencyCode.LH, Set.of(LH_KEY, "LH 묶음 2026000124"), List.of(failure(AgencyCode.LH, LH_KEY),
                failure(AgencyCode.LH, "LH 묶음 2026000124", SOURCE_LOOKUP_FAILED)), UUID.randomUUID());
        store.reconcile(AgencyCode.SH, Set.of("SH:9"), List.of(failure(AgencyCode.SH, "SH:9")), UUID.randomUUID());

        assertThat(repository.findAll()).extracting(failure -> failure.getSourceKey(),
                        failure -> failure.getSourceAnnouncementIdentifier(), failure -> failure.getPanId())
                .containsExactlyInAnyOrder(tuple(LH_KEY, null, "2026000123"),
                        tuple("LH 묶음 2026000124", null, null), tuple("SH:9", "SH:9", null));
    }

    private static AnnouncementRefinementFailure failure(AgencyCode provider, String sourceKey) {
        return failure(provider, sourceKey, SOURCE_REFINEMENT_FAILED);
    }

    private static AnnouncementRefinementFailure failure(AgencyCode provider, String sourceKey,
            AnnouncementRefinementFailureReason reason) {
        return AnnouncementRefinementFailure.observed(provider, sourceKey, reason, "원천 정제를 완료하지 못했습니다.",
                OCCURRED_AT);
    }
}
