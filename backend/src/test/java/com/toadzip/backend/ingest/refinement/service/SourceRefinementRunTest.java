package com.toadzip.backend.ingest.refinement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.toadzip.backend.ingest.refinement.domain.AnnouncementRefinementFailureReason.SOURCE_LOOKUP_FAILED;
import static com.toadzip.backend.ingest.refinement.domain.AnnouncementRefinementFailureReason.SOURCE_REFINEMENT_FAILED;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.tuple;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.pipeline.repository.IngestOwnershipLostException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import com.toadzip.backend.ingest.refinement.domain.AnnouncementRefinementFailure;
import com.toadzip.backend.ingest.refinement.repository.AnnouncementRefinementFailureStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(IngestExecutionExtension.class)
class SourceRefinementRunTest {

    private enum Outcome { CREATED, UNCHANGED }

    private final IngestWriteOwnershipGuard guard = mock(IngestWriteOwnershipGuard.class);
    private final AnnouncementRefinementFailureStore failureStore = mock(AnnouncementRefinementFailureStore.class);
    private final SourceRefinementRunner runner = new SourceRefinementRunner(mock(PlatformTransactionManager.class),
            guard, failureStore, Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void 여러_번에_나눠_정제해도_결과를_누적한다() {
        var run = runner.begin("테스트 정제", AgencyCode.SH, Outcome.class);

        run.refine(List.of("a", "b"), key -> key, key -> Outcome.CREATED);
        run.refine(List.of("c"), key -> key, key -> Outcome.UNCHANGED);

        assertThat(run.targetCount()).isEqualTo(3);
        assertThat(run.count(Outcome.CREATED)).isEqualTo(2);
        assertThat(run.count(Outcome.UNCHANGED)).isOne();
        assertThat(run.failedCount()).isZero();
    }

    @Test
    void 원천_하나가_실패하면_기록하고_다음_원천을_계속한다() {
        var run = runner.begin("테스트 정제", AgencyCode.SH, Outcome.class);

        run.refine(List.of("a", "b", "c"), key -> key, key -> {
            if (key.equals("b")) {
                throw new IllegalStateException("깨진 원천");
            }
            return Outcome.CREATED;
        });

        assertThat(run.count(Outcome.CREATED)).isEqualTo(2);
        assertThat(run.failures()).extracting(failure -> failure.sourceKey()).containsExactly("b");
    }

    @Test
    void 후속_작업은_성공한_수만_세고_실패는_기록한_뒤_다음_원천을_계속한다() {
        var run = runner.begin("테스트 정제", AgencyCode.SH, Outcome.class);

        int applied = run.apply(List.of("a", "b", "c", "d"), key -> key, key -> {
            if (key.equals("b")) {
                throw new IllegalStateException("깨진 원천");
            }
            return !key.equals("d");
        });

        assertThat(applied).isEqualTo(2);
        assertThat(run.failures()).extracting(failure -> failure.sourceKey()).containsExactly("b");
        assertThat(run.targetCount()).isZero();
    }

    @Test
    void 후속_작업_중_실행_소유권을_잃으면_실패로_삼키지_않고_던진다() {
        var run = runner.begin("테스트 정제", AgencyCode.SH, Outcome.class);
        doNothing().doThrow(new IngestOwnershipLostException()).when(guard).verifyWrite();

        assertThatThrownBy(() -> run.apply(List.of("a", "b", "c"), key -> key, key -> true))
                .isInstanceOf(IngestOwnershipLostException.class);

        assertThat(run.failedCount()).isZero();
    }

    @Test
    void 마지막_원천에서_실행_소유권을_잃어도_실패로_삼키지_않고_던진다() {
        var run = runner.begin("테스트 정제", AgencyCode.SH, Outcome.class);
        doNothing().doNothing().doThrow(new IngestOwnershipLostException()).when(guard).verifyWrite();

        assertThatThrownBy(() -> run.refine(List.of("a", "b", "c"), key -> key, key -> Outcome.CREATED))
                .isInstanceOf(IngestOwnershipLostException.class);

        assertThat(run.count(Outcome.CREATED)).isEqualTo(2);
        assertThat(run.failedCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 실행을_마치면_다룬_원천_키와_읽기_정제_실패를_기관과_함께_실패_목록에_맞춘다() {
        var run = runner.begin("테스트 정제", AgencyCode.SH, Outcome.class);
        run.refineFound("SH 페이지 1", () -> List.of("SH:1", "SH:2"), key -> key, key -> {
            if (key.equals("SH:2")) {
                throw new IllegalStateException("깨진 원천");
            }
            return Outcome.CREATED;
        });
        run.<String>refineFound("SH 페이지 3", () -> {
            throw new IllegalStateException("읽기 실패");
        }, key -> key, key -> Outcome.CREATED);

        run.recordFailures();

        ArgumentCaptor<List<AnnouncementRefinementFailure>> observed = ArgumentCaptor.forClass(List.class);
        verify(failureStore).reconcile(eq(AgencyCode.SH), eq(Set.of("SH 페이지 1", "SH:1", "SH:2", "SH 페이지 3")),
                observed.capture(), any());
        assertThat(observed.getValue()).extracting(failure -> failure.getSourceKey(), failure -> failure.getReason())
                .containsExactly(tuple("SH:2", SOURCE_REFINEMENT_FAILED), tuple("SH 페이지 3", SOURCE_LOOKUP_FAILED));
        assertThat(observed.getValue().getFirst().getDetail()).endsWith("원인: IllegalStateException");
    }
}
