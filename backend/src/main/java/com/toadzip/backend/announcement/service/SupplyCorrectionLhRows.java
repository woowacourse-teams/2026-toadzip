package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.LhSupplyRow;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyRowLinks;
import com.toadzip.backend.ingest.lh.refinement.service.LhCurrentSupplies;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공급 보정 작업실이 보여 줄 LH 공급 원본 행. LH 행은 공급행을 정하지 않는 보조 원천이라 같은 전용면적 공급행에 붙은 것으로 보여
 * 세대수 검산과 식별에만 쓴다. LH 행 수와 공급행 수가 달라도 오류가 아니다.
 */
@Component
@RequiredArgsConstructor
class SupplyCorrectionLhRows {
    private final LhCurrentSupplies lhSupplies;

    /** 공고의 최신 LH 공급 원본 행과 각 행이 붙은 공급행({@link LhRowAttachments}). LH 공고 ID가 없으면 비어 있다. */
    List<LhSupplyRow> of(Announcement announcement, List<SupplyRow> stored) {
        String panId = announcement.getLhPanId();
        if (panId == null || panId.isBlank()) { return List.of(); }
        var supplies = lhSupplies.of(panId);
        var attachments = LhRowAttachments.of(supplies, stored);
        return supplies.stream().map(supply -> {
            var attachment = attachments.get(LhSupplyRowLinks.identifier(supply));
            return new LhSupplyRow(LhSupplyRowLinks.identifier(supply), supply.getComplexLabel(), supply.getTypeName(),
                    supply.getExclusiveArea(), count(supply.getSuppliedUnitCount()),
                    attachment == null ? null : attachment.rowKey(), attachment != null && attachment.ambiguous());
        }).toList();
    }

    private static Integer count(String value) {
        if (value == null || !value.strip().matches("[0-9]{1,9}")) { return null; }
        return Integer.valueOf(value.strip());
    }
}
