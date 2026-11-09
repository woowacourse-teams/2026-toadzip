package com.toadzip.backend.ingest.lh.collection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.domain.LhLeaseCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceView;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LhSourceReadNormalizationTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void 저장된_LH_공고_목록_행을_읽으면_식별자_공백을_정리한_원천_키로_만든다() {
        var source = LhAnnouncementCatalogSourceView.read(1L, COLLECTED_AT, COLLECTED_AT, true,
                new LhAnnouncementCatalogSnapshot(" PAN-1 ", " 01 ", " 06 ", " 13 ", " 052 ", " 공고 ",
                        "공고중", null, null, null, null, null));

        assertThat(source.getSourceKey()).isEqualTo("01:06:13:PAN-1");
        assertThat(source.getPanId()).isEqualTo("PAN-1");
        assertThat(source.getSupplyInfoTypeCode()).isEqualTo("052");
    }

    @Test
    void 저장된_LH_공고_목록_행의_빈_값은_null과_같은_내용으로_본다() {
        var withNull = new LhAnnouncementCatalogSnapshot("PAN-1", "01", "06", "13", "052", "공고", "공고중",
                null, null, null, null, null);
        var withBlank = new LhAnnouncementCatalogSnapshot("PAN-1", "01", "06", "13", "052", "공고", "공고중",
                " ", "", "", "", "");

        assertThat(withNull.normalized()).isEqualTo(withBlank.normalized());
    }

    @Test
    void 저장된_LH_공급행을_읽으면_값의_앞뒤_공백을_정리한다() {
        var source = LhAnnouncementSupplySourceView.read(1L, 2, " PAN-1 ", "hash", COLLECTED_AT,
                new LhAnnouncementSupplySourceSnapshot(" 행복단지 ", " 26A ", " 26.01 ", null, " 10 ", " 5 ",
                        " 1,000 ", null));

        assertThat(source.getPanId()).isEqualTo("PAN-1");
        assertThat(source.getComplexLabel()).isEqualTo("행복단지");
        assertThat(source.getTypeName()).isEqualTo("26A");
        assertThat(source.getExclusiveArea()).isEqualTo("26.01");
        assertThat(source.getSuppliedUnitCount()).isEqualTo("5");
        assertThat(source.getDepositText()).isEqualTo("1,000");
    }

    @Test
    void 저장된_LH_임대_카탈로그_행을_읽으면_값의_앞뒤_공백을_정리한다() {
        var source = LhLeaseCatalogSourceView.read(1L, 3, COLLECTED_AT,
                new LhCatalogSourceSnapshot(" 서울 ", " 국민임대 ", " 행복단지 ", " 100 ", " 26.01 ", " 10 ",
                        null, null));

        assertThat(source.getAreaName()).isEqualTo("서울");
        assertThat(source.getSupplyTypeName()).isEqualTo("국민임대");
        assertThat(source.getComplexLabel()).isEqualTo("행복단지");
        assertThat(source.getSourceOrder()).isEqualTo(3);
    }
}
