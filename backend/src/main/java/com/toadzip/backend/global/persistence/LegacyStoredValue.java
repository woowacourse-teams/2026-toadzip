package com.toadzip.backend.global.persistence;

import java.util.Set;

public interface LegacyStoredValue {

    String legacyStoredValue();

    default Set<String> storedValues() {
        return Set.of(((Enum<?>) this).name(), legacyStoredValue());
    }
}
