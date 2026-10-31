package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.repository.external.IngestServiceKeyContext;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock.Lease;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

/**
 * 수집·정제 실행의 ID, 소유권, 진행 모니터와 서비스키를 스레드 범위로 보관한다. MDC는 로그 출력에만 쓴다.
 * 서비스키는 외부 API 클라이언트가 읽으므로 repository 계층의 {@link IngestServiceKeyContext}에 둔다.
 */
public final class IngestExecutionScope implements AutoCloseable {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private final State previous;

    private IngestExecutionScope(State state) {
        previous = CURRENT.get();
        set(state);
    }

    public static IngestExecutionScope open(Lease lease) {
        State current = currentState();
        return new IngestExecutionScope(new State(current.executionId(), lease, current.monitor(), current.serviceKey()));
    }

    public static IngestExecutionScope open(Lease lease, DataPipelineExecutionMonitor monitor) {
        State current = currentState();
        return new IngestExecutionScope(new State(current.executionId(), lease, monitor, current.serviceKey()));
    }

    public static IngestExecutionScope open(
            UUID executionId, Lease lease, DataPipelineExecutionMonitor monitor, String serviceKey
    ) {
        return new IngestExecutionScope(new State(executionId, lease, monitor, serviceKey));
    }

    public static Optional<UUID> currentExecutionId() {
        return Optional.ofNullable(currentState().executionId());
    }

    public static void checkStopRequested() {
        monitor().ifPresent(DataPipelineExecutionMonitor::checkStopRequested);
    }

    public static void beginWork(String label, String unit, long total) {
        monitor().ifPresent(monitor -> monitor.beginWork(label, unit, total));
    }

    public static void workCompleted() {
        monitor().ifPresent(DataPipelineExecutionMonitor::workCompleted);
    }

    public static void pageCompleted(int page, int totalRows, int pageSize) {
        monitor().ifPresent(monitor -> monitor.pageCompleted(page, totalRows, pageSize));
    }

    public static void requestStarted(String description) {
        monitor().ifPresent(monitor -> monitor.requestStarted(description));
    }

    public static void requestFinished() {
        monitor().ifPresent(DataPipelineExecutionMonitor::requestFinished);
    }

    public static Optional<Lease> current() {
        return Optional.ofNullable(currentState().lease());
    }

    public static void verifyHeld() {
        current().ifPresent(Lease::verifyHeld);
    }

    /** 실행 맥락과 로그 MDC를 작업 스레드에 전달하고 작업 뒤 이전 상태로 복원한다. */
    public static <T> Callable<T> propagate(Callable<T> task) {
        State captured = CURRENT.get();
        Map<String, String> capturedLog = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousLog = MDC.getCopyOfContextMap();
            replaceLog(capturedLog);
            try (var ignored = new IngestExecutionScope(captured)) {
                verifyHeld();
                checkStopRequested();
                return task.call();
            }
            finally {
                replaceLog(previousLog);
            }
        };
    }

    private static State currentState() {
        State current = CURRENT.get();
        if (current == null) {
            return State.EMPTY;
        }
        return current;
    }

    private static Optional<DataPipelineExecutionMonitor> monitor() {
        return Optional.ofNullable(currentState().monitor());
    }

    private static void set(State state) {
        IngestServiceKeyContext.set(state == null ? null : state.serviceKey());
        if (state == null || state.equals(State.EMPTY)) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(state);
    }

    private static void replaceLog(Map<String, String> log) {
        if (log == null) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(log);
    }

    @Override
    public void close() {
        set(previous);
    }

    private record State(UUID executionId, Lease lease, DataPipelineExecutionMonitor monitor, String serviceKey) {

        private static final State EMPTY = new State(null, null, null, null);
    }
}
