package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 마이홈 정제가 공고 한 건을 쓴 같은 트랜잭션 안에서 LH 보강에 맡기는 일이다.
 * 보강할 수 없으면 {@link LhAnnouncementEnrichmentRejectedException}을 던진다.
 */
public interface MappedAnnouncementLhEnrichment {

    void writeAfterMapping(
            Announcement announcement,
            LhAnnouncementRequest request,
            List<LhAnnouncementSupplySourceView> supplies,
            Set<Long> changedHousingTypeRowIds,
            Set<String> sourceKeysExcludedFromLhEnrichment,
            Map<String, LhSupplyRowLinks.State> supplyLinks
    );

    /** 공고가 LH 소유에서 벗어나면 관리자가 고치지 않은 LH 일정·첨부·공급대상을 지운다. */
    void releaseLhOwnership(Announcement announcement);

    /**
     * 관리자가 LH 공급 연결을 다른 원천행으로 옮긴 LH 행({@code movedLhRows})을 이전 공급행에서 놓고, 이전 공급행에 붙은
     * 그 LH 행의 금액 가운데 관리자가 고치지 않은 금액을 지운다. {@code resolvedSourceRows}는 LH 행마다 지금 해석되는
     * 마이홈 원천행이다.
     */
    void releaseMovedLhRows(Announcement announcement, Map<String, String> resolvedSourceRows, Set<String> movedLhRows);
}
