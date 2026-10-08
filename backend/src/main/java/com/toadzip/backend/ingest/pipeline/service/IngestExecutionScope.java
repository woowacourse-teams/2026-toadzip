package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.repository.external.IngestServiceKeyContext;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock.Lease;
import java.util.Optional;
import java.util.concurrent.Callable;

public final class IngestExecutionScope implements AutoCloseable {

    private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();

    private static final ThreadLocal<DataPipelineExecutionMonitor> MONITOR = new ThreadLocal<>();

    private final Lease previous;
    private final DataPipelineExecutionMonitor previousMonitor;
    private final String previousServiceKey;

    private IngestExecutionScope(Lease lease, DataPipelineExecutionMonitor monitor, String serviceKey) {
        previous = CURRENT.get();
        previousMonitor = MONITOR.get();
        previousServiceKey = IngestServiceKeyContext.current().orElse(null);
        IngestServiceKeyContext.set(serviceKey);
        setMonitor(monitor);
        if (lease == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(lease);
    }

    public static IngestExecutionScope open(Lease lease) {
        return new IngestExecutionScope(lease, MONITOR.get(), IngestServiceKeyContext.current().orElse(null));
    }

    public static IngestExecutionScope open(Lease lease, DataPipelineExecutionMonitor monitor) {
        return new IngestExecutionScope(lease, monitor, IngestServiceKeyContext.current().orElse(null));
    }

    public static IngestExecutionScope open(Lease lease, DataPipelineExecutionMonitor monitor, String serviceKey) {
        return new IngestExecutionScope(lease, monitor, serviceKey);
    }

    public static void checkStopRequested() {
        if (MONITOR.get() != null) {
            MONITOR.get().checkStopRequested();
        }
    }

    public static void beginWork(String label, String unit, long total) {
        if (MONITOR.get() != null) {
            MONITOR.get().beginWork(label, unit, total);
        }
    }

    public static void workCompleted() {
        if (MONITOR.get() != null) {
            MONITOR.get().workCompleted();
        }
    }

    public static void pageCompleted(int page, int totalRows, int pageSize) {
        if (MONITOR.get() != null) {
            MONITOR.get().pageCompleted(page, totalRows, pageSize);
        }
    }

    public static void requestStarted(String description) {
        if (MONITOR.get() != null) {
            MONITOR.get().requestStarted(description);
        }
    }

    public static void requestFinished() {
        if (MONITOR.get() != null) {
            MONITOR.get().requestFinished();
        }
    }

    public static Optional<Lease> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void verifyHeld() {
        current().ifPresent(Lease::verifyHeld);
    }

    public static <T> Callable<T> propagate(Callable<T> task) {
        Lease captured = CURRENT.get();
        DataPipelineExecutionMonitor capturedMonitor = MONITOR.get();
        String capturedServiceKey = IngestServiceKeyContext.current().orElse(null);
        return () -> {
            try (var ignored = open(captured, capturedMonitor, capturedServiceKey)) {
                verifyHeld();
                checkStopRequested();
                return task.call();
            }
        };
    }

    private static void setMonitor(DataPipelineExecutionMonitor monitor) {
        if (monitor == null) {
            MONITOR.remove();
            return;
        }
        MONITOR.set(monitor);
    }

    @Override
    public void close() {
        IngestServiceKeyContext.set(previousServiceKey);
        setMonitor(previousMonitor);
        if (previous == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(previous);
    }
}
