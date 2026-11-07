package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.HousingTypeAreas;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyRowLinks;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceView;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * LH 공급 원천 줄이 붙는 공급행. 대응은 저장하지 않고 면적으로 계산한다. 같은 공고 단지에서 LH 전용면적(소수 둘째 자리)이 공급행의
 * 연결 주택형·LH 대표·공고 면적 중 하나와 같은 공급행이다. LH 줄에 면적이 없으면 그 줄로 만든 공급행(LH 원천 식별자)이다.
 * 후보가 여럿이면(다시 보정하지 않아 신청자 묶음마다 나뉜 옛 공급행) 표시 순서상 첫 행에 붙이고 모호로 알린다.
 * 모든 후보에 붙이면 LH 세대수가 중복으로 더해지기 때문이다.
 */
final class LhRowAttachments {

    private LhRowAttachments() {
    }

    record Attachment(String rowKey, boolean ambiguous) {
    }

    /** LH 줄 식별자("LH:{panId}:SUPPLY:{순번}")별 붙은 공급행. 붙지 않은 줄은 없다. */
    static Map<String, Attachment> of(List<LhAnnouncementSupplySourceView> supplies, List<SupplyRow> stored) {
        List<SupplyRow> rows = stored.stream().filter(row -> !row.isAdminExcluded())
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        boolean singleComplex = supplies.stream().map(LhAnnouncementSupplySourceView::getComplexLabel).distinct().count() <= 1;
        Map<String, Attachment> result = new LinkedHashMap<>();
        for (var supply : supplies) {
            String identifier = LhSupplyRowLinks.identifier(supply);
            BigDecimal area = area(supply.getExclusiveArea());
            List<SupplyRow> candidates = rows.stream()
                    .filter(row -> sameComplex(row, supply.getComplexLabel(), singleComplex))
                    .filter(row -> area != null ? areas(row).anyMatch(rowArea -> HousingTypeAreas.sameArea(rowArea, area))
                            : identifier.equals(row.getLhSourceSupplyRowIdentifier()))
                    .toList();
            if (!candidates.isEmpty()) {
                result.put(identifier, new Attachment(key(candidates.getFirst()), candidates.size() > 1));
            }
        }
        return result;
    }

    /** 작업실이 공급행을 가리키는 키. 작업실 행·원천·LH 대응이 모두 이 키를 쓴다. */
    static String key(SupplyRow row) {
        return "row:" + row.getId();
    }

    // 공고 단지를 모르는 행은 공고 단지가 하나뿐인 공고에서만 그 단지 행으로 본다.
    private static boolean sameComplex(SupplyRow row, String complexLabel, boolean singleComplex) {
        if (row.getAnnouncementComplex() == null) {
            return singleComplex;
        }
        return row.getAnnouncementComplex().getSourceComplexName().equals(complexLabel);
    }

    private static Stream<BigDecimal> areas(SupplyRow row) {
        return Stream.concat(row.getHousingTypes().stream().map(HousingType::getExclusiveArea),
                Stream.of(row.getLhExclusiveArea(), row.getAnnouncementExclusiveArea())).filter(Objects::nonNull);
    }

    private static BigDecimal area(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.strip());
        }
        catch (NumberFormatException unreadable) {
            return null;
        }
    }
}
