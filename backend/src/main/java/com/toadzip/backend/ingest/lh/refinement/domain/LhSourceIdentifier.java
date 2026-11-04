package com.toadzip.backend.ingest.lh.refinement.domain;

import java.util.Collection;
import java.util.Optional;

/**
 * LH 원천에서 만든 식별자 규칙이다. LH 원천 행은 "LH:{LH 공고}:{종류}:{순번}"을,
 * 마이홈 공고에 LH 공급으로 만든 공급행은 "{공고}:LH:{LH 공고}:{순번}"을, LH 공급으로 만든 공급대상은
 * "{LH 공급 원천 식별자}:TARGET"을, LH 공고 정제가 LH 원천 소유 공고에 만든 공급행은
 * "LHROW:{공고 단지 ID}:{정규화한 주택형명}#{같은 이름 안 순번}"을 쓴다.
 */
public final class LhSourceIdentifier {

    private static final String PREFIX = "LH:";
    private static final String LH_OWNED_SUPPLY_ROW_PREFIX = "LHROW:";

    private LhSourceIdentifier() {
    }

    public static String source(String panId, String datasetType, Integer sourceOrder) {
        return PREFIX + panId + ":" + datasetType + ":" + sourceOrder;
    }

    public static String supply(String panId, Integer sourceOrder) {
        return source(panId, "SUPPLY", sourceOrder);
    }

    public static String target(String supplyIdentifier) {
        return supplyIdentifier + ":TARGET";
    }

    /** LH 공급 원천 식별자 "LH:{LH 공고}:SUPPLY:{순번}"에서 LH 공고와 순번을 꺼낸다. 다른 형식이면 비운다. */
    public static Optional<Supply> parseSupply(String identifier) {
        String[] parts = identifier == null ? new String[0] : identifier.split(":");
        if (parts.length != 4 || !parts[0].equals("LH") || !parts[2].equals("SUPPLY")) {
            return Optional.empty();
        }
        return Optional.of(new Supply(parts[1], parts[3]));
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

    public static String lhOwnedSupplyRow(Long announcementComplexId, String normalizedTypeName, int occurrence) {
        return LH_OWNED_SUPPLY_ROW_PREFIX + announcementComplexId + ":" + normalizedTypeName + "#" + occurrence;
    }

    private static String supplyRowPrefix(String announcementIdentifier) {
        return announcementIdentifier + ":" + PREFIX;
    }

    public record Supply(String panId, String sourceOrder) {
    }
}
