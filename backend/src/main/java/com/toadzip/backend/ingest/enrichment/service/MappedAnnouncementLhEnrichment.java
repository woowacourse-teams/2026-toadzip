package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 마이홈 정제가 공고 한 건을 쓴 같은 트랜잭션 안에서 LH 보강에 맡기는 일이다.
 * 보강할 수 없으면 {@link LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentRejectedException}을 던진다.
 */
public interface MappedAnnouncementLhEnrichment {

    void writeAfterMapping(
            Announcement announcement,
            LhAnnouncementRequest request,
            List<LhAnnouncementSupplySource> supplies,
            Set<Long> changedHousingTypeRowIds,
            Set<String> sourceKeysExcludedFromLhEnrichment,
            Map<String, LhSupplyRowLinks.State> supplyLinks
    );

    /** 공고가 LH 소유에서 벗어나면 관리자가 고치지 않은 LH 일정·첨부·공급대상을 지운다. */
    void releaseLhOwnership(Announcement announcement);
}
