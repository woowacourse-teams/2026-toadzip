package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.SourceSupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyRowLinks;
import com.toadzip.backend.ingest.lh.refinement.service.LhCurrentSupplies;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PDF 추출 작업에 담을 입력 스냅숏을 만든다. 원천 행은 공급 보정 작업실과 같은 원천에서 같은 행 식별자로 담아, 워커가 돌려준 원천 행을
 * 화면이 작업실 행과 식별자로 맞춘다. LH 공고는 작업실의 lhSupplyRows와 같은 LH 공급 원천(LhCurrentSupplies),
 * GH 공고는 작업실의 rowSources(GH_ANNOUNCEMENT)와 같은 제외하지 않은 GH 공급행 틀이다. 그 밖의 공고는 공급 원천이 없다.
 */
@Component
@RequiredArgsConstructor
class PdfExtractionInputs {
    private final AnnouncementRepository announcements;
    private final SupplyRowRepository supplyRows;
    private final LhCurrentSupplies lhSupplies;

    /** 공급행의 공고를 지연 로딩하므로(ghFrameRow) 한 트랜잭션에서 읽는다. */
    @Transactional(readOnly = true)
    PdfExtractionInput read(long announcementId) {
        return new PdfExtractionInput(announcements.findById(announcementId).map(this::sourceRows).orElse(List.of()));
    }

    private List<SourceSupplyRow> sourceRows(Announcement announcement) {
        String panId = announcement.getLhPanId();
        if (panId != null && !panId.isBlank()) { return lhRows(panId); }
        return supplyRows.findAllByAnnouncement(announcement).stream()
                .filter(row -> row.ghFrameRow() && !row.isAdminExcluded())
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId))
                .map(PdfExtractionInputs::ghRow).toList();
    }

    private List<SourceSupplyRow> lhRows(String panId) {
        return lhSupplies.of(panId).stream().map(supply -> new SourceSupplyRow("LH", supply.getSourceOrder(),
                LhSupplyRowLinks.identifier(supply), supply.getComplexLabel(), supply.getTypeName(),
                supply.getExclusiveArea(), supply.getSupplyArea(), supply.getSuppliedUnitCount(),
                supply.getTotalUnitCount())).toList();
    }

    private static SourceSupplyRow ghRow(SupplyRow row) {
        return new SourceSupplyRow("GH", row.getDisplayOrder(), row.getSourceSupplyRowIdentifier(),
                row.getSourceComplexName(), row.getSourceHousingTypeName(), area(row.getAnnouncementExclusiveArea()), "",
                Objects.toString(row.getTotalSupplyHouseholdCount(), ""), "");
    }

    private static String area(BigDecimal value) {
        if (value == null) { return ""; }
        return value.stripTrailingZeros().toPlainString();
    }
}
