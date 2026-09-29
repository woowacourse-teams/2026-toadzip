package com.toadzip.backend.ingest.failure.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngestFailureReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void 중복된_진행중_실패는_마지막_행에_관찰을_반영한다() {
        var first = failure("first");
        var last = failure("last");
        var created = IngestFailureReconciler.reconcile(
                List.of(first, last), List.of(), List.of(failure("observed")), NOW, EXECUTION_ID
        );

        assertThat(first.getOccurrenceCount()).isEqualTo(1);
        assertThat(last.getOccurrenceCount()).isEqualTo(2);
        assertThat(last.getDetail()).isEqualTo("observed");
        assertThat(created).isEmpty();
    }

    @Test
    void 진행중_실패가_있으면_해결된_과거_행보다_우선한다() {
        var pending = failure("pending");
        var historical = failure("history");
        historical.resolve(NOW, EXECUTION_ID);

        IngestFailureReconciler.reconcile(
                List.of(pending), List.of(historical), List.of(failure("observed")), NOW, EXECUTION_ID
        );

        assertThat(pending.getOccurrenceCount()).isEqualTo(2);
        assertThat(historical.getStatus()).isEqualTo(IngestFailureStatus.RESOLVED);
    }

    @Test
    void 과거_실패만_있으면_첫_행을_재발_처리한다() {
        var first = failure("first");
        var last = failure("last");
        first.resolve(NOW, EXECUTION_ID);
        last.resolve(NOW, EXECUTION_ID);

        IngestFailureReconciler.reconcile(
                List.of(), List.of(first, last), List.of(failure("observed")), NOW, EXECUTION_ID
        );

        assertThat(first.getRecurrenceCount()).isEqualTo(1);
        assertThat(first.getLastExecutionId()).isEqualTo(EXECUTION_ID);
        assertThat(last.getStatus()).isEqualTo(IngestFailureStatus.RESOLVED);
    }

    @Test
    void 같은_실패를_여러_번_관찰하면_마지막_값을_한_번만_추가한다() {
        var first = failure("first");
        var last = failure("last");

        var created = IngestFailureReconciler.reconcile(
                List.of(), List.of(), List.of(first, last), NOW, EXECUTION_ID
        );

        assertThat(created).containsExactly(last);
        assertThat(last.getFirstExecutionId()).isEqualTo(EXECUTION_ID);
        assertThat(last.getOccurrenceCount()).isEqualTo(1);
    }

    @Test
    void 더이상_관찰되지_않는_실패만_해결한다() {
        var pending = failure("pending");

        var created = IngestFailureReconciler.reconcile(
                List.of(pending), List.of(), List.of(), NOW, EXECUTION_ID
        );

        assertThat(pending.getStatus()).isEqualTo(IngestFailureStatus.RESOLVED);
        assertThat(pending.getLastResolvedExecutionId()).isEqualTo(EXECUTION_ID);
        assertThat(created).isEmpty();
    }

    private LhAnnouncementEnrichmentFailure failure(String detail) {
        return LhAnnouncementEnrichmentFailure.create(
                "source-1", "announcement-1", "pan-1",
                LhAnnouncementEnrichmentFailureReason.INVALID_VALUE, detail, NOW
        );
    }
}
