package com.toadzip.backend.ingest.lh.refinement.domain;

import java.util.Collection;

/**
 * LH 원천에서 만든 식별자 규칙이다. LH 원천 행은 "LH:{LH 공고}:{종류}:{순번}"을 쓴다.
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

    public static boolean isLhSourceOfAny(String identifier, Collection<String> panIds) {
        return identifier != null && panIds.stream().anyMatch(panId -> identifier.startsWith(PREFIX + panId + ":"));
    }
}
