package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class LhAnnouncementSupplyMatcher {

    LhSupplyMatchResult match(List<SupplyRow> rows, LhSupplyData source) {
        for (SupplyRow row : rows) {
            if (source.sourceIdentifier().equals(row.getLhSourceSupplyRowIdentifier())
                    && matchesComplex(row, source)
                    && matchesHousingType(row, source)) {
                return LhSupplyMatchResult.matched(row);
            }
        }
        List<SupplyRow> complexMatches = rows.stream()
                .filter(row -> matchesComplex(row, source))
                .toList();
        if (complexMatches.isEmpty()) {
            return LhSupplyMatchResult.failure(
                    source,
                    LhAnnouncementEnrichmentFailureReason.COMPLEX_NOT_FOUND,
                    "LH 공급 원본과 일치하는 기존 공급 단지가 없습니다."
            );
        }
        List<SupplyRow> typeMatches = complexMatches.stream()
                .filter(row -> matchesHousingType(row, source))
                .toList();
        if (typeMatches.size() == 1) {
            return LhSupplyMatchResult.matched(typeMatches.getFirst());
        }
        if (typeMatches.size() > 1) {
            return LhSupplyMatchResult.failure(
                    source,
                    LhAnnouncementEnrichmentFailureReason.AMBIGUOUS_HOUSING_TYPE,
                    "LH 공급 원본에 일치하는 주택형 공급행이 여러 개입니다."
            );
        }
        if (complexMatches.size() == 1) {
            return LhSupplyMatchResult.failure(
                    source,
                    LhAnnouncementEnrichmentFailureReason.HOUSING_TYPE_NOT_FOUND,
                    "LH 공급 원본과 일치하는 기존 주택형 공급행이 없습니다."
            );
        }
        return LhSupplyMatchResult.failure(
                source,
                LhAnnouncementEnrichmentFailureReason.AMBIGUOUS_COMPLEX,
                "LH 공급 원본에 일치하는 기존 공급 단지가 여러 개입니다."
        );
    }

    private boolean matchesComplex(SupplyRow row, LhSupplyData source) {
        if (SupplyNameNormalizer.sameComplex(row.getSourceComplexName(), source.complexName())) {
            return true;
        }
        return row.getHousingComplex() != null
                && SupplyNameNormalizer.sameComplex(row.getHousingComplex().getName(), source.complexName());
    }

    private boolean matchesHousingType(SupplyRow row, LhSupplyData source) {
        if (same(row.getSourceHousingTypeName(), source.housingTypeName())) {
            return true;
        }
        return row.getHousingType() != null
                && same(row.getHousingType().getName(), source.housingTypeName());
    }

    private boolean same(String left, String right) {
        return normalized(left).equals(normalized(right));
    }

    private String normalized(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").replace("-", "").strip().toLowerCase();
    }
}

record LhSupplyMatchResult(SupplyRow row, LhSupplyMatchingFailureData failure) {

    static LhSupplyMatchResult matched(SupplyRow row) {
        return new LhSupplyMatchResult(row, null);
    }

    static LhSupplyMatchResult failure(
            LhSupplyData source,
            LhAnnouncementEnrichmentFailureReason reason,
            String detail
    ) {
        return new LhSupplyMatchResult(null, new LhSupplyMatchingFailureData(source, reason, detail));
    }
}
