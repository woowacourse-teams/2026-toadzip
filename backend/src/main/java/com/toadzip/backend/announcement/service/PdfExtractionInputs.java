package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.AgencySupplyLine;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.Publication;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.SourceSupplyRow;
import com.toadzip.backend.announcement.repository.AgencySupplyLineRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.lh.refinement.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.lh.refinement.service.LhCurrentSupplies;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PDF 추출 작업에 담을 입력 스냅숏(원천 공급 행)을 만든다. LH 공고는 공급 작업실의 원천 참고 행과 같은 LH 공급 원천(LhCurrentSupplies),
 * GH 공고는 GH 준비 줄(지구의 주택형마다 한 줄)이다. 그 밖의 공고는 공급 원천이 없다.
 */
@Component
class PdfExtractionInputs {
    private final AnnouncementRepository announcements;
    private final AgencySupplyLineRepository lines;
    private final LhCurrentSupplies lhSupplies;
    private final PublicationMode mode;

    PdfExtractionInputs(AnnouncementRepository announcements, AgencySupplyLineRepository lines,
            LhCurrentSupplies lhSupplies, @Value("${announcement.pdf-publication.mode}") PublicationMode mode) {
        this.announcements = announcements;
        this.lines = lines;
        this.lhSupplies = lhSupplies;
        this.mode = mode;
    }

    @Transactional(readOnly = true)
    PdfExtractionInput read(long announcementId) {
        return announcements.findById(announcementId)
                .map(announcement -> new PdfExtractionInput(sourceRows(announcement), announcement.getSupplyType(), null))
                .orElseGet(() -> new PdfExtractionInput(List.of()));
    }

    @Transactional(readOnly = true)
    PdfExtractionInput readBatch(long announcementId, long attachmentId) {
        var announcement = announcements.findById(announcementId).orElseThrow();
        return new PdfExtractionInput(sourceRows(announcement), announcement.getSupplyType(),
                publication(announcement, attachmentId));
    }

    /** 자동 공개를 껐거나 공급을 이미 확정한 공고는 자동 공개 대상이 아니라 스냅숏을 담지 않는다. */
    private Publication publication(Announcement announcement, long attachmentId) {
        if (mode == PublicationMode.OFF || announcement.isSupplyConfirmed()) {
            return null;
        }
        return new Publication(attachmentId, announcement.getVersion(), announcement.getName(), mode,
                announcement.getSupplyType(), announcement.getRecruitmentType(),
                announcement.getApplicationStartDate(), announcement.getApplicationEndDate(),
                announcement.getWinnerAnnouncementDate());
    }

    private List<SourceSupplyRow> sourceRows(Announcement announcement) {
        String panId = announcement.getLhPanId();
        if (panId != null && !panId.isBlank()) {
            return lhRows(panId);
        }
        return lines.findAllByAnnouncementIdIn(List.of(announcement.getId())).stream()
                .filter(line -> line.getAgency() == AgencyCode.GH).map(PdfExtractionInputs::ghRow).toList();
    }

    private List<SourceSupplyRow> lhRows(String panId) {
        return lhSupplies.of(panId).stream().map(supply -> new SourceSupplyRow("LH", supply.getSourceOrder(),
                LhSourceIdentifier.supply(supply.getPanId(), supply.getSourceOrder()), supply.getComplexLabel(),
                supply.getTypeName(), supply.getExclusiveArea(), supply.getSupplyArea(),
                supply.getSuppliedUnitCount(), supply.getTotalUnitCount())).toList();
    }

    private static SourceSupplyRow ghRow(AgencySupplyLine line) {
        return new SourceSupplyRow("GH", line.getDisplayOrder(), line.getSourceLineKey(), line.getComplexName(),
                line.getHousingTypeName(), area(line.getExclusiveArea()), "",
                Objects.toString(line.getHouseholdCount(), ""), "");
    }

    private static String area(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
