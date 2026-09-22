package com.toadzip.backend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

public final class MigrationSqlSection {

    private static final String RECONCILIATION = "db/migration/V20260922_01__reconcile_existing_schema.sql";
    private static final String SECTION_PREFIX = "-- Applied state from ";

    private MigrationSqlSection() {
    }

    public static Resource productionSchemaSnapshot() {
        String script = readScript("db/migration/B20260922_01__initial_schema.sql");
        int end = script.indexOf(SECTION_PREFIX);
        if (end < 0) {
            throw new IllegalStateException("Production schema snapshot not found");
        }
        return new ByteArrayResource(script.substring(0, end).getBytes(StandardCharsets.UTF_8));
    }

    public static Resource resource(String formerMigrationName) {
        String script = readScript(RECONCILIATION);
        String marker = SECTION_PREFIX + formerMigrationName;
        int start = script.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Migration section not found: " + marker);
        }
        start += marker.length();
        int nextSection = script.indexOf(SECTION_PREFIX, start);
        int nextReconciliation = script.indexOf("-- Reconcile constraints skipped", start);
        int end = nextSection >= 0 ? nextSection : nextReconciliation;
        if (end < 0) {
            throw new IllegalStateException("Migration section end not found: " + marker);
        }
        return new ByteArrayResource(script.substring(start, end).getBytes(StandardCharsets.UTF_8));
    }

    private static String readScript(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
