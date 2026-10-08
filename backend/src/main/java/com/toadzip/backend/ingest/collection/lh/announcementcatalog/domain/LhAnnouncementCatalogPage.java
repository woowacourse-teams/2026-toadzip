package com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain;

import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;

public record LhAnnouncementCatalogPage(
        SourcePage<LhAnnouncementCatalogRow> page, String startDate, String endDate
) {

    public void validateWindow(LhAnnouncementCatalogPage first) {
        if (!startDate.equals(first.startDate) || !endDate.equals(first.endDate)) {
            throw new IllegalArgumentException("LH 공고 목록의 검색 기간이 페이지 사이에 변경됐습니다.");
        }
    }
}
