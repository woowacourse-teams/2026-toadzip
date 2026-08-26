package com.toadzip.backend.ingest.dto;

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

    public String requestCode() {
        return requestCode;
    }
}
