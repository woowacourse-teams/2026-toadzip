package com.toadzip.backend.ingest.collection.domain;

public record LhAnnouncementCatalogSnapshot(
        String panId,
        String connectionSystemDivisionCode,
        String upperAnnouncementTypeCode,
        String announcementTypeCode,
        String supplyInfoTypeCode,
        String announcementName,
        String status,
        String noticeDate,
        String publicationDate,
        String closingDate,
        String detailUrl,
        String mobileDetailUrl
) {

    public String sourceKey() {
        return String.join(":", connectionSystemDivisionCode, upperAnnouncementTypeCode,
                announcementTypeCode, panId);
    }
}
