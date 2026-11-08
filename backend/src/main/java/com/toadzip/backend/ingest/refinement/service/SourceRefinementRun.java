package com.toadzip.backend.ingest.refinement.service;

import com.toadzip.backend.ingest.domain.SourceRefinementFailure;
import com.toadzip.backend.ingest.pipeline.repository.IngestOwnershipLostException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 한 번의 실행 동안 여러 번에 나눠 받은 원천을 정제하고 결과를 누적한다. 원천마다 따로 커밋해 한 원천의 실패가 앞서 정제한
 * 공고를 되돌리지 않게 하고, 원천 하나가 실패하면 기록한 뒤 다음 원천을 계속한다. 실행 소유권을 잃은 경우만은 실패로 삼키지
 * 않고 그대로 던져 실행을 멈춘다.
 */
@Slf4j
public final class SourceRefinementRun<O extends Enum<O>> {

    private final String label;
    private final TransactionTemplate transactions;
    private final IngestWriteOwnershipGuard ownershipGuard;
    private final Map<O, Integer> outcomes;
    private final List<SourceRefinementFailure> failures = new ArrayList<>();
    private int targetCount;
    private boolean reportsProgress;

    SourceRefinementRun(String label, Class<O> outcomeType, TransactionTemplate transactions,
            IngestWriteOwnershipGuard ownershipGuard) {
        this.label = label;
        this.transactions = transactions;
        this.ownershipGuard = ownershipGuard;
        outcomes = new EnumMap<>(outcomeType);
    }

    /**
     * 정제할 원천을 읽는 일까지 원천 정제와 같은 규칙으로 돌린다. 읽다 실패하면 {@code lookupKey}로 실패를 기록하고 이번 묶음은
     * 건너뛰어, 이미 받은 수집 결과가 보고서와 함께 남게 한다. 읽기는 원천마다 쓰는 트랜잭션과 같은 방식으로 열어 필요한 준비도
     * 한 곳에서 한다.
     */
    public <S> void refineFound(String lookupKey, Supplier<List<S>> lookup, Function<S, String> sourceKey,
            Function<S, O> refiner) {
        refineFound(lookupKey, lookup, sourceKey, refiner, (source, outcome) -> { });
    }

    public <S> void refineFound(String lookupKey, Supplier<List<S>> lookup, Function<S, String> sourceKey,
            Function<S, O> refiner, BiConsumer<S, O> committed) {
        List<S> sources = attempt(lookupKey, lookup).orElse(List.of());
        refine(sources, sourceKey, refiner, committed);
    }

    /** {@link #refineFound}의 {@link #apply} 판이다. 대상을 읽다 실패하면 {@code lookupKey}로 실패를 기록하고 건너뛴다. */
    public <S> int applyFound(String lookupKey, Supplier<List<S>> lookup, Function<S, String> sourceKey,
            Predicate<S> work, Consumer<S> committed) {
        return apply(attempt(lookupKey, lookup).orElse(List.of()), sourceKey, work, committed);
    }

    /** 원천 하나를 마칠 때마다 실행 진행률에 알린다. 정제할 원천 수를 미리 알리는 전체 정제가 쓴다. */
    public SourceRefinementRun<O> reportingProgress() {
        reportsProgress = true;
        return this;
    }

    /** 주어진 원천을 차례로 정제한다. 호출하는 쪽이 정제 순서에 맞게 정렬해 넘긴다. */
    public <S> void refine(List<S> sources, Function<S, String> sourceKey, Function<S, O> refiner) {
        refine(sources, sourceKey, refiner, (source, outcome) -> { });
    }

    /**
     * {@code committed}는 원천의 트랜잭션이 커밋된 뒤에만 부른다. 커밋 때 실패해 되돌아간 원천의 집계를 남기지 않으려는
     * 호출자가 쓴다.
     */
    public <S> void refine(List<S> sources, Function<S, String> sourceKey, Function<S, O> refiner,
            BiConsumer<S, O> committed) {
        for (S source : sources) {
            targetCount++;
            try {
                attempt(sourceKey.apply(source), () -> refiner.apply(source)).ifPresent(outcome -> {
                    outcomes.merge(outcome, 1, Integer::sum);
                    committed.accept(source, outcome);
                });
            }
            finally {
                if (reportsProgress) {
                    IngestExecutionScope.workCompleted();
                }
            }
        }
    }

    /**
     * 정제 결과로 세지 않는 후속 작업(마무리 재연결)을 같은 규칙으로 돌린다. 원천마다 따로 커밋하고, 실패는 정제 실패와
     * 함께 기록한다. 작업이 true를 돌려준 수를 반환하고, 그 원천마다 커밋 뒤 {@code committed}를 부른다.
     */
    public <S> int apply(List<S> sources, Function<S, String> sourceKey, Predicate<S> work) {
        return apply(sources, sourceKey, work, source -> { });
    }

    public <S> int apply(List<S> sources, Function<S, String> sourceKey, Predicate<S> work, Consumer<S> committed) {
        int applied = 0;
        for (S source : sources) {
            if (attempt(sourceKey.apply(source), () -> work.test(source)).orElse(false)) {
                applied++;
                committed.accept(source);
            }
        }
        return applied;
    }

    private <T> Optional<T> attempt(String sourceKey, Supplier<T> work) {
        IngestExecutionScope.checkStopRequested();
        try {
            return Optional.ofNullable(transactions.execute(status -> {
                ownershipGuard.verifyWrite();
                return work.get();
            }));
        }
        catch (IngestOwnershipLostException lost) {
            throw lost;
        }
        catch (RuntimeException failure) {
            failures.add(SourceRefinementFailure.of(sourceKey, failure));
            log.warn("{}에 실패했습니다. sourceKey={}", label, sourceKey, failure);
            return Optional.empty();
        }
    }

    public int targetCount() {
        return targetCount;
    }

    public int count(O outcome) {
        return outcomes.getOrDefault(outcome, 0);
    }

    public int failedCount() {
        return failures.size();
    }

    public List<SourceRefinementFailure> failures() {
        return List.copyOf(failures);
    }
}
