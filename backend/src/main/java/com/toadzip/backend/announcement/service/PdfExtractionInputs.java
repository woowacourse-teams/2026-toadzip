package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyRowLinks;
import com.toadzip.backend.ingest.lh.refinement.service.LhCurrentSupplies;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PDF 추출 작업에 담을 입력 스냅숏을 만든다. LH 행은 공급 보정 작업실과 같은 LH 공급 원천(LhCurrentSupplies)에서 같은 행 식별자로 담아,
 * 워커가 돌려준 LH 행을 화면이 작업실의 lhSupplyRows와 식별자로 맞춘다.
 */
@Component
@RequiredArgsConstructor
class PdfExtractionInputs {
    private final AnnouncementRepository announcements;
    private final LhCurrentSupplies lhSupplies;

    PdfExtractionInput read(long announcementId) {
        String panId = announcements.findById(announcementId).map(Announcement::getLhPanId).orElse(null);
        if (panId == null || panId.isBlank()) { return new PdfExtractionInput(List.of()); }
        return new PdfExtractionInput(lhSupplies.of(panId).stream().map(supply -> new PdfExtractionInput.LhSupplyRow(
                supply.getSourceOrder(), LhSupplyRowLinks.identifier(supply), supply.getComplexLabel(), supply.getTypeName(),
                supply.getExclusiveArea(), supply.getSupplyArea(), supply.getSuppliedUnitCount(), supply.getTotalUnitCount()))
                .toList());
    }
}
