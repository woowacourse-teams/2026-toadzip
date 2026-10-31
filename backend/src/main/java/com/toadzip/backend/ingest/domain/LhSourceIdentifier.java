package com.toadzip.backend.ingest.domain;

import java.util.Collection;

/**
 * LH 원천에서 만든 식별자 규칙이다. LH 원천 행은 "LH:{LH 공고}:{종류}:{순번}"을,
 * 마이홈 공고에 LH 공급으로 만든 공급행은 "{공고}:LH:{LH 공고}:{순번}"을 쓴다.
 */
public final class LhSourceIdentifier {

    private static final String PREFIX = "LH:";

    private LhSourceIdentifier() {
    }

    public static String source(String panId, String datasetType, Integer sourceOrder) {
        return PREFIX + panId + ":" + datasetType + ":" + sourceOrder;
    }

    public static String supply(String panId, Integer sourceOrder) {
        return source(panId, "SUPPLY", sourceOrder);
    }

    public static boolean isLhSource(String identifier) {
        return identifier != null && identifier.startsWith(PREFIX);
    }

    public static boolean isLhSourceOfAny(String identifier, Collection<String> panIds) {
        return identifier != null && panIds.stream().anyMatch(panId -> identifier.startsWith(PREFIX + panId + ":"));
    }

    public static String supplyRow(String announcementIdentifier, String panId, Integer sourceOrder) {
        return supplyRowPrefix(announcementIdentifier) + panId + ":" + sourceOrder;
    }

    public static boolean isSupplyRowOf(String announcementIdentifier, String identifier) {
        return identifier.startsWith(supplyRowPrefix(announcementIdentifier));
    }

    private static String supplyRowPrefix(String announcementIdentifier) {
        return announcementIdentifier + ":" + PREFIX;
    }
}
