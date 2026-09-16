package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.collection.domain.LhProviderPolicy;
import java.util.Map;
import java.util.Set;

class MyHomeAnnouncementClassificationPolicy {

    private static final Map<String, RentalType> RENTAL_TYPES = Map.ofEntries(
            Map.entry("행복주택", RentalType.HAPPY_HOUSING),
            Map.entry("국민임대", RentalType.NATIONAL_RENTAL),
            Map.entry("영구임대", RentalType.PERMANENT_RENTAL),
            Map.entry("50년임대", RentalType.PUBLIC_RENTAL_50Y),
            Map.entry("50년공공임대", RentalType.PUBLIC_RENTAL_50Y),
            Map.entry("통합공공임대", RentalType.INTEGRATED_PUBLIC_RENTAL),
            Map.entry("재개발임대", RentalType.REDEVELOPMENT_RENTAL)
    );
    private static final Map<String, String> COMPLEX_SUPPLY_TYPES = Map.ofEntries(
            Map.entry("행복주택", "HAPPY_HOUSING"),
            Map.entry("국민임대", "NATIONAL_RENTAL"),
            Map.entry("영구임대", "PERMANENT_RENTAL"),
            Map.entry("5년임대", "PUBLIC_RENTAL_5Y"),
            Map.entry("10년임대", "PUBLIC_RENTAL_10Y"),
            Map.entry("50년임대", "PUBLIC_RENTAL_50Y"),
            Map.entry("50년공공임대", "PUBLIC_RENTAL_50Y"),
            Map.entry("통합공공임대", "INTEGRATED_PUBLIC_RENTAL"),
            Map.entry("재개발임대", "REDEVELOPMENT_RENTAL")
    );

    private final MyHomeAnnouncementValueParser parser;

    MyHomeAnnouncementClassificationPolicy(MyHomeAnnouncementValueParser parser) {
        this.parser = parser;
    }

    AnnouncementPublicationType publicationType(String status, String previousIdentifier) {
        if (status.contains("취소")) {
            requirePreviousIdentifier(previousIdentifier, "취소공고");
            return AnnouncementPublicationType.CANCELLATION;
        }
        if (previousIdentifier != null) {
            return AnnouncementPublicationType.CORRECTION;
        }
        if (status.contains("정정")) {
            throw parser.missing("정정공고의 이전 공고 식별자");
        }
        return AnnouncementPublicationType.ORIGINAL;
    }

    private void requirePreviousIdentifier(String previousIdentifier, String status) {
        if (previousIdentifier == null) {
            throw parser.missing(status + "의 이전 공고 식별자");
        }
    }

    RentalType rentalType(String value) {
        return RENTAL_TYPES.getOrDefault(value, RentalType.ETC);
    }

    String complexSupplyType(String value) {
        String supplyType = COMPLEX_SUPPLY_TYPES.get(value);
        if (supplyType == null) {
            throw parser.invalid("지원하지 않는 단지 공급유형입니다: " + value);
        }
        return supplyType;
    }

    AgencyCode provider(String value) {
        if (LhProviderPolicy.isLh(value)) {
            return AgencyCode.LH;
        }
        if (Set.of("SH공사", "서울주택도시공사").contains(value)) {
            return AgencyCode.SH;
        }
        if (value.equals("경기주택도시공사")) {
            return AgencyCode.GH;
        }
        return AgencyCode.ETC;
    }

    RecruitmentType recruitmentType(String name) {
        if (name.contains("예비")) {
            return RecruitmentType.WAITLIST;
        }
        if (name.contains("모집")) {
            return RecruitmentType.NEW;
        }
        return RecruitmentType.ETC;
    }

    SupplyCategory supplyCategory(String announcementName) {
        if (announcementName.contains("예비")
                || announcementName.contains("추가")
                || announcementName.contains("재공급")) {
            return SupplyCategory.RESUPPLY;
        }
        return SupplyCategory.NEW_SUPPLY;
    }
}
