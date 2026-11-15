package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.AgencySupplyLine;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowSeed;
import com.toadzip.backend.announcement.domain.SupplySheetSeed;
import com.toadzip.backend.announcement.repository.AgencySupplyLineRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** LH 공고가 아닌 공고의 PDF 추출 입력. GH 공고는 GH 준비 줄을, SH 공고는 공급 원천이 없어 아무 행도 담지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PdfExtractionInputsIntegrationTest {

    @Autowired private PdfExtractionInputs inputs;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private SupplyRowRepository supplyRows;
    @Autowired private AgencySupplyLineRepository lines;
    @Autowired private EntityManager entities;

    @Test
    void GH_공고는_GH_준비_줄을_원천_줄_키와_표시_순서로_담는다() {
        Announcement announcement = announcement("GH:9101", AgencyCode.GH);
        line(announcement, "GH:9101:다산:36A", 1, "36A 청년", new BigDecimal("36.8000"), 60);
        line(announcement, "GH:9101:다산:33A", 0, "33A", null, null);
        // 공고 공급표(정본)에 관리자가 넣은 행은 원천 틀이 아니다.
        frame(announcement, "MANUAL:added", 3, "51A", 10);
        supplyRows.flush();

        PdfExtractionInput input = inputs.read(announcement.getId());

        assertThat(input.sourceSupplyRows()).containsExactly(
                new PdfExtractionInput.SourceSupplyRow("GH", 0, "GH:9101:다산:33A", "다산", "33A", "", "", "", ""),
                new PdfExtractionInput.SourceSupplyRow("GH", 1, "GH:9101:다산:36A", "다산", "36A 청년", "36.8", "", "60",
                        ""));
    }

    @Test
    void SH_공고는_공급_원천_행이_없어_PDF_공급행을_새로_추가한다() {
        Announcement announcement = announcement("SH:9102", AgencyCode.SH);
        frame(announcement, "SH:9102:row", 0, "36A", 10);
        supplyRows.flush();

        assertThat(inputs.read(announcement.getId()).sourceSupplyRows()).isEmpty();
    }

    private Announcement announcement(String identifier, AgencyCode provider) {
        return announcements.saveAndFlush(Announcement.createUnconfirmed(identifier, "입주자 모집공고", provider,
                LocalDate.of(2026, 7, 10), "https://example.com/" + identifier));
    }

    private void line(Announcement announcement, String key, int order, String typeName, BigDecimal area,
            Integer count) {
        lines.save(AgencySupplyLine.create(announcement, AgencyCode.GH, new AgencySupplyLine.Values(key, order, null,
                "다산", typeName, area, null, null, count, null, null, null, SupplyCategory.NEW_SUPPLY)));
    }

    private SupplyRow frame(Announcement announcement, String key, int order, String typeName, Integer count) {
        SupplyRow row = supplyRows.save(SupplyRowSeed.create(entities, announcement, null, null, order, "다산", typeName, null,
                null, SupplyCategory.NEW_SUPPLY));
        new SupplySheetSeed(entities).row(row, count);
        return row;
    }
}
