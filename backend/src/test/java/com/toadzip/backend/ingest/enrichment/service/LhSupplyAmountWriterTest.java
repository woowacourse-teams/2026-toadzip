package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowGroup;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementSupplyMatcher.LhSupplyMatchResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LhSupplyAmountWriterTest {

    @Mock
    private SupplyRowRepository supplyRowRepository;

    @Mock
    private SupplyTargetRepository supplyTargetRepository;

    @Mock
    private LhAnnouncementSupplyMatcher supplyMatcher;

    @Test
    void 공고의_공급대상을_한번_조회해_보강과_정리에_재사용한다() {
        Announcement announcement = mock(Announcement.class);
        SupplyRow row = mock(SupplyRow.class);
        LhSupplyData supply = new LhSupplyData("LH:100:1", "단지", "주택형", null,
                null, null, null, null);
        when(supplyRowRepository.findAllByAnnouncement(announcement)).thenReturn(List.of(row));
        when(row.getId()).thenReturn(1L);
        when(supplyMatcher.match(List.of(row), supply)).thenReturn(LhSupplyMatchResult.matched(row));

        writer().write(announcement, new LhAnnouncementEnrichmentData(
                "100", null, null, List.of(), List.of(), List.of(supply), false
        ), Set.of(), Set.of());

        verify(supplyTargetRepository).findAllBySupplyRowIdIn(List.of(1L));
        verify(supplyTargetRepository, never()).findAllBySupplyRow(any());
    }

    @Test
    void 서로_다른_LH_공급행이_같은_제품_공급행에_연결되면_반영_전에_거절한다() {
        Announcement announcement = mock(Announcement.class);
        SupplyRow row = mock(SupplyRow.class);
        LhSupplyData first = new LhSupplyData("LH:100:SUPPLY:1", "단지", "주택형", null,
                null, null, null, null);
        LhSupplyData second = new LhSupplyData("LH:100:SUPPLY:2", "단지", "주택형", null,
                null, null, null, null);
        when(supplyRowRepository.findAllByAnnouncement(announcement)).thenReturn(List.of(row));
        when(row.getId()).thenReturn(1L);
        when(supplyMatcher.match(List.of(row), first)).thenReturn(LhSupplyMatchResult.matched(row));
        when(supplyMatcher.match(List.of(row), second)).thenReturn(LhSupplyMatchResult.matched(row));
        LhAnnouncementEnrichmentData data = new LhAnnouncementEnrichmentData(
                "100", null, null, List.of(), List.of(), List.of(first, second), false
        );

        assertThatThrownBy(() -> writer().write(announcement, data, Set.of(), Set.of()))
                .isInstanceOf(LhAnnouncementEnrichmentRejectedException.class)
                .hasMessageContaining("여러 LH 공급행");

        verify(row, never()).enrichFromLh(any(), any(), any());
        verify(supplyTargetRepository, never()).save(any());
    }

    @Test
    void LH_공급_원천이_비면_LH_금액에만_보존_사유를_남기고_수기_금액은_그대로_둔다() {
        Announcement announcement = mock(Announcement.class);
        SupplyRow row = mock(SupplyRow.class);
        when(row.getId()).thenReturn(1L);
        SupplyTarget lh = target(row, "LH:100:SUPPLY:0:TARGET");
        SupplyTarget manual = target(row, "MANUAL:1");
        when(supplyRowRepository.findAllByAnnouncement(announcement)).thenReturn(List.of(row));
        when(supplyTargetRepository.findAllBySupplyRowIdIn(List.of(1L))).thenReturn(List.of(lh, manual));

        writer().write(announcement, new LhAnnouncementEnrichmentData(
                "100", null, null, List.of(), List.of(), List.of(), false
        ), Set.of(), Set.of());

        assertThat(lh.getLhAmountPreservedReason()).isEqualTo("LH_SUPPLY_NOT_PROVIDED");
        assertThat(manual.getLhAmountPreservedReason()).isNull();
        verify(supplyTargetRepository, never()).deleteAll(any());
    }

    @Test
    void 관리자가_고친_LH_금액은_LH_공급_식별자가_바뀌어도_새_금액으로_대신하지_않는다() {
        Announcement announcement = mock(Announcement.class);
        SupplyRow row = mock(SupplyRow.class);
        when(row.getId()).thenReturn(1L);
        SupplyTarget reviewed = target(row, "LH:100:SUPPLY:0:TARGET");
        reviewed.correctPresentation(false, 1);
        LhSupplyData supply = new LhSupplyData("LH:100:SUPPLY:1", "단지", "주택형", null,
                null, 10, new BigDecimal("2000000"), new BigDecimal("200000"));
        when(supplyRowRepository.findAllByAnnouncement(announcement)).thenReturn(List.of(row));
        when(supplyTargetRepository.findAllBySupplyRowIdIn(List.of(1L))).thenReturn(List.of(reviewed));
        when(supplyMatcher.match(List.of(row), supply)).thenReturn(LhSupplyMatchResult.matched(row));
        lenient().when(supplyTargetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        writer().write(announcement, new LhAnnouncementEnrichmentData(
                "100", null, null, List.of(), List.of(), List.of(supply), false
        ), Set.of(), Set.of());

        verify(supplyTargetRepository, never()).save(any());
        assertThat(reviewed.getRentalDeposit()).isEqualByComparingTo("1000000");
    }

    @Test
    void 복수_공급행으로_보정한_묶음_행은_LH_공급_매칭_대상에서_뺀다() {
        Announcement announcement = mock(Announcement.class);
        SupplyRow grouped = mock(SupplyRow.class);
        SupplyRow plain = mock(SupplyRow.class);
        when(grouped.getSupplyGroup()).thenReturn(mock(SupplyRowGroup.class));
        when(plain.getId()).thenReturn(2L);
        LhSupplyData supply = new LhSupplyData("LH:100:SUPPLY:0", "단지", "주택형", null,
                null, null, null, null);
        when(supplyRowRepository.findAllByAnnouncement(announcement)).thenReturn(List.of(grouped, plain));
        when(supplyMatcher.match(List.of(grouped, plain), supply)).thenReturn(LhSupplyMatchResult.matched(plain));
        when(supplyMatcher.match(List.of(plain), supply)).thenReturn(LhSupplyMatchResult.matched(plain));

        writer().write(announcement, new LhAnnouncementEnrichmentData(
                "100", null, null, List.of(), List.of(), List.of(supply), false
        ), Set.of(), Set.of());

        verify(supplyMatcher).match(List.of(plain), supply);
        verify(plain).enrichFromLh("LH:100:SUPPLY:0", null, null);
    }

    private static SupplyTarget target(SupplyRow row, String identifier) {
        return SupplyTarget.createFromSource(row, identifier, "전체", "전체", 10,
                new BigDecimal("1000000"), new BigDecimal("100000"), 1);
    }

    private LhSupplyAmountWriter writer() {
        return new LhSupplyAmountWriter(supplyRowRepository, new LhEnrichmentSupplyRows(supplyRowRepository, supplyMatcher),
                supplyTargetRepository, supplyMatcher);
    }
}
