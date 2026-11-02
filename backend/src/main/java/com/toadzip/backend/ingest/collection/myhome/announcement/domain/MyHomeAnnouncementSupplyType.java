package com.toadzip.backend.ingest.collection.myhome.announcement.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum MyHomeAnnouncementSupplyType {
    PERMANENT_RENTAL("01"),
    NATIONAL_RENTAL("02"),
    FIFTY_YEAR_RENTAL("03"),
    TEN_YEAR_RENTAL("05"),
    FIVE_YEAR_RENTAL("06"),
    HAPPY_HOUSE("10"),
    INTEGRATED_PUBLIC_RENTAL("12");

    private final String requestCode;

    MyHomeAnnouncementSupplyType(String requestCode) {
        this.requestCode = requestCode;
    }

    public static Set<String> requestCodes() {
        return Arrays.stream(values()).map(MyHomeAnnouncementSupplyType::requestCode)
                .collect(Collectors.toUnmodifiableSet());
    }

    public String requestCode() {
        return requestCode;
    }
}
