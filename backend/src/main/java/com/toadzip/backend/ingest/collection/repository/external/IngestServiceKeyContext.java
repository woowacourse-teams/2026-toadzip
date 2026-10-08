package com.toadzip.backend.ingest.collection.repository.external;

import java.util.Optional;

public final class IngestServiceKeyContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private IngestServiceKeyContext() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void set(String serviceKey) {
        if (serviceKey == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(serviceKey);
    }
}
