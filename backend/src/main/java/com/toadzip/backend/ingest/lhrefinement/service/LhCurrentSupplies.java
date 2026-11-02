package com.toadzip.backend.ingest.lhrefinement.service;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogSourceReader;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhAnnouncementSupplySourceReader;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** LH 공고 정제가 공급행으로 펼치는 LH 공급 원천 행. 정제와 같은 요청으로 읽어 정제 결과와 비교할 수 있게 한다. */
@Component
@RequiredArgsConstructor
public class LhCurrentSupplies {

    private final LhAnnouncementCatalogSourceReader catalog;
    private final LhAnnouncementSupplySourceReader supplies;

    /** 최신 공고목록에 그 공고 ID가 하나만 있을 때 그 공고의 LH 공급 원천 행. 목록에 없거나 여럿이면 비어 있다. */
    public List<LhAnnouncementSupplySourceView> of(String panId) {
        List<LhAnnouncementCatalogSourceView> entries = catalog.findAllByPanIdInAndPresentInLatestCatalogTrue(List.of(panId));
        if (entries.size() != 1) {
            return List.of();
        }
        LhAnnouncementRequest request = LhAnnouncementRequest.from(entries.getFirst());
        return supplies.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(request.panId(), request.requestHash());
    }
}
