package com.toadzip.backend.ingest.refinement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.toadzip.backend.ingest.pipeline.repository.IngestOwnershipLostException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(IngestExecutionExtension.class)
class SourceRefinementRunTest {

    private enum Outcome { CREATED, UNCHANGED }

    private final IngestWriteOwnershipGuard guard = mock(IngestWriteOwnershipGuard.class);
    private final SourceRefinementRunner runner = new SourceRefinementRunner(mock(PlatformTransactionManager.class), guard);

    @Test
    void 여러_번에_나눠_정제해도_결과를_누적한다() {
        var run = runner.begin("테스트 정제", Outcome.class);

        run.refine(List.of("a", "b"), key -> key, key -> Outcome.CREATED);
        run.refine(List.of("c"), key -> key, key -> Outcome.UNCHANGED);

        assertThat(run.targetCount()).isEqualTo(3);
        assertThat(run.count(Outcome.CREATED)).isEqualTo(2);
        assertThat(run.count(Outcome.UNCHANGED)).isOne();
        assertThat(run.failedCount()).isZero();
    }

    @Test
    void 원천_하나가_실패하면_기록하고_다음_원천을_계속한다() {
        var run = runner.begin("테스트 정제", Outcome.class);

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
        var run = runner.begin("테스트 정제", Outcome.class);

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
        var run = runner.begin("테스트 정제", Outcome.class);
        doNothing().doThrow(new IngestOwnershipLostException()).when(guard).verifyWrite();

        assertThatThrownBy(() -> run.apply(List.of("a", "b", "c"), key -> key, key -> true))
                .isInstanceOf(IngestOwnershipLostException.class);

        assertThat(run.failedCount()).isZero();
    }

    @Test
    void 마지막_원천에서_실행_소유권을_잃어도_실패로_삼키지_않고_던진다() {
        var run = runner.begin("테스트 정제", Outcome.class);
        doNothing().doNothing().doThrow(new IngestOwnershipLostException()).when(guard).verifyWrite();

        assertThatThrownBy(() -> run.refine(List.of("a", "b", "c"), key -> key, key -> Outcome.CREATED))
                .isInstanceOf(IngestOwnershipLostException.class);

        assertThat(run.count(Outcome.CREATED)).isEqualTo(2);
        assertThat(run.failedCount()).isZero();
    }
}
