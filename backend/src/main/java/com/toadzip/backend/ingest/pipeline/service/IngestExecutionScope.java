package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock.Lease;
import java.util.Optional;
import java.util.concurrent.Callable;

public final class IngestExecutionScope implements AutoCloseable {

    private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();

    private final Lease previous;

    private IngestExecutionScope(Lease lease) {
        previous = CURRENT.get();
        if (lease == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(lease);
    }

    public static IngestExecutionScope open(Lease lease) {
        return new IngestExecutionScope(lease);
    }

    public static Optional<Lease> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void verifyHeld() {
        current().ifPresent(Lease::verifyHeld);
    }

    public static <T> Callable<T> propagate(Callable<T> task) {
        Lease captured = CURRENT.get();
        return () -> {
            try (var ignored = open(captured)) {
                verifyHeld();
                return task.call();
            }
        };
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(previous);
    }
}
