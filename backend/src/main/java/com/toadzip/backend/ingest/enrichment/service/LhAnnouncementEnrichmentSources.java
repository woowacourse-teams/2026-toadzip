package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceView;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhAnnouncementDetailSourceReader;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhAnnouncementSupplySourceReader;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 연결된 LH 요청의 상세·공급 원천을 보강 입력으로 만든다. 전체 보강과 정제 뒤 보강이 같은 입력을 쓴다. */
@Component
@RequiredArgsConstructor
class LhAnnouncementEnrichmentSources {

    private final LhAnnouncementDetailSourceReader details;
    private final LhAnnouncementSupplySourceReader supplySources;
    private final LhAnnouncementEnrichmentMapper mapper;

    /**
     * 상세 원천이 없으면 거절한다. 공급 원천이 비었을 때 그 요청의 공급이 없다고 확인된 수집이면
     * {@link LhAnnouncementEnrichmentData#verifiedEmptySupply()}가 참이다.
     */
    LhAnnouncementEnrichmentData read(LhAnnouncementRequest request, List<LhAnnouncementSupplySourceView> supplies,
            Map<String, LhSupplyRowLinks.State> supplyLinks) {
        List<LhAnnouncementDetailSourceView> detailSources = details
                .findAllByPanIdAndRequestHashOrderBySourceOrderAsc(request.panId(), request.requestHash());
        if (detailSources.isEmpty()) {
            throw new LhAnnouncementEnrichmentRejectedException(
                    LhAnnouncementEnrichmentFailureReason.LH_DETAIL_SOURCE_NOT_FOUND,
                    "연결된 LH 공고 상세 원본이 없습니다."
            );
        }
        boolean verifiedEmptySupply = supplies.isEmpty()
                && supplySources.hasVerifiedEmptySupplies(request.panId(), request.requestDescription());
        return mapper.map(request.panId(), detailSources, supplies).withSupplyLinks(supplyLinks)
                .withVerifiedEmptySupply(verifiedEmptySupply);
    }
}
