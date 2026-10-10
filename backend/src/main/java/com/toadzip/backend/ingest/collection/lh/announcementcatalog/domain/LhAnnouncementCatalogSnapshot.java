package com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain;

import java.util.Arrays;

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
        return String.join(":", normalized(connectionSystemDivisionCode), normalized(upperAnnouncementTypeCode),
                normalized(announcementTypeCode), normalized(panId));
    }

    public void validateIdentifiers() {
        for (String value : Arrays.asList(panId, connectionSystemDivisionCode,
                upperAnnouncementTypeCode, announcementTypeCode, supplyInfoTypeCode)) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("LH 공고 목록의 필수 식별자가 비어 있습니다.");
            }
        }
    }

    public LhAnnouncementCatalogSnapshot normalized() {
        return new LhAnnouncementCatalogSnapshot(
                normalized(panId),
                normalized(connectionSystemDivisionCode),
                normalized(upperAnnouncementTypeCode),
                normalized(announcementTypeCode),
                normalized(supplyInfoTypeCode),
                normalized(announcementName),
                normalized(status),
                normalized(noticeDate),
                normalized(publicationDate),
                normalized(closingDate),
                normalized(detailUrl),
                normalized(mobileDetailUrl));
    }

    private static String normalized(String value) {
        if (value == null) {
            return "";
        }
        return value.strip();
    }
}
