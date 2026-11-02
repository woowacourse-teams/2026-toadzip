package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowGroup;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import java.util.List;
import org.junit.jupiter.api.Test;

class LhEnrichmentSupplyRowsTest {

    private final LhEnrichmentSupplyRows supplyRows = new LhEnrichmentSupplyRows(
            mock(SupplyRowRepository.class), new LhAnnouncementSupplyMatcher());

    @Test
    void 묶음_행에_매칭되는_LH_공급은_건너뛴다() {
        var rows = new LhEnrichmentSupplyRows.Rows(List.of(row("평화단지", "46A", true), row("동삼단지", "59B", false)));

        assertThat(supplyRows.skips(rows, supply("평화단지", "46A"))).isTrue();
        assertThat(supplyRows.skips(rows, supply("동삼단지", "59B"))).isFalse();
    }

    @Test
    void 매칭되는_행이_없어도_같은_단지_행이_모두_묶음이면_건너뛴다() {
        var allGrouped = new LhEnrichmentSupplyRows.Rows(List.of(row("평화단지", "46A", true)));
        var partlyGrouped = new LhEnrichmentSupplyRows.Rows(
                List.of(row("평화단지", "46A", true), row("평화단지", "51C", false)));

        assertThat(supplyRows.skips(allGrouped, supply("평화단지", "84D"))).isTrue();
        assertThat(supplyRows.skips(partlyGrouped, supply("평화단지", "84D"))).isFalse();
    }

    @Test
    void 묶음_행이_없는_공고에서는_건너뛰지_않는다() {
        var rows = new LhEnrichmentSupplyRows.Rows(List.of(row("평화단지", "46A", false)));

        assertThat(supplyRows.skips(rows, supply("평화단지", "84D"))).isFalse();
    }

    static SupplyRow row(String complexName, String housingTypeName, boolean grouped) {
        SupplyRow row = mock(SupplyRow.class);
        lenient().when(row.getSourceComplexName()).thenReturn(complexName);
        lenient().when(row.getSourceHousingTypeName()).thenReturn(housingTypeName);
        lenient().when(row.getSupplyGroup()).thenReturn(grouped ? mock(SupplyRowGroup.class) : null);
        return row;
    }

    static LhSupplyData supply(String complexName, String housingTypeName) {
        return new LhSupplyData("LH:100:SUPPLY:0", complexName, housingTypeName, null, null, null, null, null);
    }
}
