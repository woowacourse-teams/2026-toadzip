package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentWriter.LhSupplyMatchingFailureData;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class LhAnnouncementSupplyMatcher {

    LhSupplyMatchResult match(List<SupplyRow> rows, LhSupplyData source) {
        if (source.linkedRow() != null && source.linkedRow().reviewRequired()) {
            return LhSupplyMatchResult.failure(
                    source,
                    LhAnnouncementEnrichmentFailureReason.LH_SUPPLY_LINK_REVIEW_REQUIRED,
                    "저장한 LH 공급 연결의 원천이 변경되었습니다. 근거를 다시 확인하고 연결을 저장해 주세요."
            );
        }
        // 정제가 이 LH 행으로 직접 만든 공급행이면 단지명 표기가 달라도 같은 단지다(아산읍내 ↔ 아산읍내1 영구임대아파트).
        for (SupplyRow row : rows) {
            if (source.sourceIdentifier().equals(row.getLhSourceSupplyRowIdentifier())
                    && matchesHousingType(row, source)) {
                return LhSupplyMatchResult.matched(row);
            }
        }
        List<SupplyRow> complexMatches = complexCandidates(rows, source);
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

    /** 관리자가 저장한 연결은 단지명 대신 그 마이홈 원천행으로 만든 공급행만 후보로 둔다. */
    private List<SupplyRow> complexCandidates(List<SupplyRow> rows, LhSupplyData source) {
        if (source.linkedRow() != null) {
            String linkedRowIdentifier = source.linkedRow().sourceRowIdentifier();
            return rows.stream()
                    .filter(row -> linkedRowIdentifier.equals(row.getMyhomeSourceRowIdentifier()))
                    .toList();
        }
        return rows.stream()
                .filter(row -> matchesComplex(row, source))
                .toList();
    }

    private boolean matchesComplex(SupplyRow row, LhSupplyData source) {
        if (SupplyNameNormalizer.sameComplex(row.getSourceComplexName(), source.complexName())) {
            return true;
        }
        return row.getHousingComplex() != null
                && SupplyNameNormalizer.sameComplex(row.getHousingComplex().getName(), source.complexName());
    }

    private boolean matchesHousingType(SupplyRow row, LhSupplyData source) {
        if (SupplyNameNormalizer.sameHousingType(row.getSourceHousingTypeName(), source.housingTypeName())) {
            return true;
        }
        return row.getHousingType() != null
                && SupplyNameNormalizer.sameHousingType(row.getHousingType().getName(), source.housingTypeName());
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
}
