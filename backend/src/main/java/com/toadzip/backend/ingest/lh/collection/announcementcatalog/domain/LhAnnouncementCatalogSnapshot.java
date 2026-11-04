package com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain;

import java.util.Arrays;
import java.util.Set;

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

    private static final String RENTAL_HOUSING = "06";
    /** 공급정보 조회를 지원하는 임대주택 공급유형. 어린이집 운영자 모집(064) 등은 제외한다. */
    private static final Set<String> RENTAL_SUPPLY_INFO_TYPES = Set.of("060", "061", "062", "063");
    private static final String NEWLYWED_HOPE = "39";
    /** 신혼희망타운(39) 중 임대인 행복주택(신혼희망, 42)과 통합공공임대(신혼희망, 51). 공공분양(39)은 제외한다. */
    private static final Set<String> NEWLYWED_HOPE_RENTAL_TYPES = Set.of("42", "51");

    /** 건설임대 공고인지. 토지·상가·분양·매입임대 공고는 수집·정제 대상이 아니다. */
    public boolean isRentalHousingAnnouncement() {
        LhAnnouncementCatalogSnapshot value = normalized();
        return RENTAL_HOUSING.equals(value.upperAnnouncementTypeCode)
                && RENTAL_SUPPLY_INFO_TYPES.contains(value.supplyInfoTypeCode)
                || NEWLYWED_HOPE.equals(value.upperAnnouncementTypeCode)
                && NEWLYWED_HOPE_RENTAL_TYPES.contains(value.announcementTypeCode);
    }

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
