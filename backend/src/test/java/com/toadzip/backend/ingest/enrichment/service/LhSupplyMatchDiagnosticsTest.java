package com.toadzip.backend.ingest.enrichment.service;

import static com.toadzip.backend.ingest.enrichment.service.LhEnrichmentSupplyRowsTest.row;
import static com.toadzip.backend.ingest.enrichment.service.LhEnrichmentSupplyRowsTest.supply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LhSupplyMatchDiagnosticsTest {

    private final AnnouncementRepository announcements = mock(AnnouncementRepository.class);
    private final SupplyRowRepository supplyRowRepository = mock(SupplyRowRepository.class);
    private final LhAnnouncementEnrichmentMapper mapper = mock(LhAnnouncementEnrichmentMapper.class);
    private final LhAnnouncementSupplyMatcher matcher = new LhAnnouncementSupplyMatcher();
    private final LhSupplyMatchDiagnostics diagnostics = new LhSupplyMatchDiagnostics(announcements,
            new LhEnrichmentSupplyRows(supplyRowRepository, matcher), mapper, matcher);

    @Test
    void 같은_단지_행이_모두_묶음이면_보강처럼_건너뛰어_실패로_보이지_않는다() {
        List<SupplyRow> rows = List.of(row("평화단지", "46A", true));

        assertThat(diagnose(rows, supply("평화단지", "84D"))).isEmpty();
    }

    @Test
    void 묶음이_아닌_행과_매칭하지_못한_LH_공급은_실패와_후보로_보인다() {
        List<SupplyRow> rows = List.of(row("평화단지", "46A", true), row("평화단지", "51C", false));

        assertThat(diagnose(rows, supply("평화단지", "84D"))).singleElement()
                .satisfies(failure -> assertThat(failure.candidates()).isNotEmpty());
    }

    private List<LhSupplyMatchDiagnostics.Failure> diagnose(List<SupplyRow> rows, LhSupplyData source) {
        Announcement announcement = mock(Announcement.class);
        LhAnnouncementSupplySourceView supply = mock(LhAnnouncementSupplySourceView.class);
        when(announcements.findBySourceAnnouncementIdentifier("A-1")).thenReturn(Optional.of(announcement));
        when(supplyRowRepository.findAllByAnnouncement(announcement)).thenReturn(rows);
        when(mapper.map(eq("100"), anyList(), any())).thenReturn(new LhAnnouncementEnrichmentData(
                "100", null, null, List.of(), List.of(), List.of(source), false));
        return diagnostics.diagnose("A-1", "100", List.of(supply), Map.of(), Set.of());
    }
}
