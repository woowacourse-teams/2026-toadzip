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
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId))
                .toList();
        Map<String, List<SupplyRow>> groups = new LinkedHashMap<>();
        String myHomeSourceIdentifier = null;
        for (SupplyRow row : orderedRows) {
            if (!row.getSourceSupplyRowIdentifier().startsWith(generatedIdentifierPrefix)) {
                myHomeSourceIdentifier = row.getSourceSupplyRowIdentifier();
            }
            if (myHomeSourceIdentifier != null) {
                groups.computeIfAbsent(myHomeSourceIdentifier, ignored -> new ArrayList<>()).add(row);
            }
        }
        return groups;
    }
}
