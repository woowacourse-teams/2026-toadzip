package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.history.dto.SourceCollectionRequest;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock.Lease;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionMonitor;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineStoppedException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SourceCollectionAttemptTest {

    private final SourceCollectionRecordService records = mock(SourceCollectionRecordService.class);
    private final SourceCollectionRequest attempt = mock(SourceCollectionRequest.class);

    @Test
    void 수집_이력을_열고_모은_원천을_저장한_결과를_돌려준다() {
        UUID recordId = UUID.randomUUID();
        when(records.start(attempt)).thenReturn(recordId);

        var stored = new AtomicReference<String>();

        UUID returned = SourceCollectionAttempt.prepare(() -> attempt).run(records, request -> "rows",
                (id, request, rows) -> stored.set(id + ":" + rows));

        assertThat(returned).isEqualTo(recordId);
        assertThat(stored).hasValue(recordId + ":rows");
        verify(records, never()).fail(any(), any(), any());
    }

    @Test
    void 중지_요청이_있으면_요청을_만들지_않아_수집_이력을_열_수_없다() {
        var monitor = mock(DataPipelineExecutionMonitor.class);
        doThrow(new DataPipelineStoppedException()).when(monitor).checkStopRequested();
        var created = new AtomicBoolean();

        try (var ignored = IngestExecutionScope.open(null, monitor)) {
            assertThatThrownBy(() -> SourceCollectionAttempt.prepare(() -> {
                created.set(true);
                return attempt;
            })).isInstanceOf(DataPipelineStoppedException.class);
        }

        assertThat(created).isFalse();
        verify(records, never()).start(any());
    }

    @Test
    void 원천을_모은_뒤_소유권을_잃으면_저장하지_않고_이력을_실패로_닫는다() {
        UUID recordId = UUID.randomUUID();
        when(records.start(attempt)).thenReturn(recordId);
        var lost = new IllegalStateException("실행 소유권을 잃었습니다.");
        var lease = mock(Lease.class);
        doNothing().doThrow(lost).when(lease).verifyHeld();
        var stored = new AtomicBoolean();

        try (var ignored = IngestExecutionScope.open(lease)) {
            assertThatThrownBy(() -> SourceCollectionAttempt.prepare(() -> attempt).run(records, request -> "rows",
                    (id, request, rows) -> stored.set(true))).isSameAs(lost);
        }

        assertThat(stored).isFalse();
        verify(records).fail(recordId, attempt, lost);
    }

    @Test
    void 원천_수집이_실패하면_이력을_실패로_닫고_같은_예외를_던진다() {
        UUID recordId = UUID.randomUUID();
        when(records.start(attempt)).thenReturn(recordId);
        var failure = new IllegalArgumentException("페이지 간 전체 건수가 다릅니다.");

        assertThatThrownBy(() -> SourceCollectionAttempt.prepare(() -> attempt).run(records, request -> {
            throw failure;
        }, (id, request, rows) -> {
        })).isSameAs(failure);

        verify(records).fail(recordId, attempt, failure);
    }

    @Test
    void 저장에_실패하면_이력을_실패로_닫고_같은_예외를_던진다() {
        UUID recordId = UUID.randomUUID();
        when(records.start(attempt)).thenReturn(recordId);
        var failure = new IllegalStateException("수집 기록을 찾을 수 없습니다.");

        assertThatThrownBy(() -> SourceCollectionAttempt.prepare(() -> attempt).run(records, request -> "rows",
                (id, request, rows) -> {
                    throw failure;
                })).isSameAs(failure);

        verify(records).fail(recordId, attempt, failure);
    }
}
