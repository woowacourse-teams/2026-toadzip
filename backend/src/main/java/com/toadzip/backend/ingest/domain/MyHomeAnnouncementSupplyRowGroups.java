package com.toadzip.backend.ingest.domain;

import com.toadzip.backend.announcement.domain.SupplyRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MyHomeAnnouncementSupplyRowGroups {

    private MyHomeAnnouncementSupplyRowGroups() {
    }

    public static Map<String, List<SupplyRow>> byMyHomeSource(
            String announcementIdentifier,
            Collection<SupplyRow> rows
    ) {
        String generatedIdentifierPrefix = announcementIdentifier + ":LH:";
        List<SupplyRow> orderedRows = rows.stream()
                .filter(row -> row.getSupplyGroup() == null)
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId))
                .toList();
        Map<String, List<SupplyRow>> groups = new LinkedHashMap<>();
        for (SupplyRow row : orderedRows) {
            String owner = row.getMyhomeSourceRowIdentifier();
            String identifier = row.getSourceSupplyRowIdentifier();
            if (owner == null && !identifier.startsWith(generatedIdentifierPrefix)
                    && !identifier.startsWith("MANUAL:") && !identifier.startsWith("ADMIN:")) {
                owner = identifier;
            }
            if (owner != null) {
                groups.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(row);
            }
        }
        return groups;
    }
}
