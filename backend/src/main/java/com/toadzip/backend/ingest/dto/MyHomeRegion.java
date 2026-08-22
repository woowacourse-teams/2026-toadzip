package com.toadzip.backend.ingest.dto;

public record MyHomeRegion(String provinceCode, String districtCode, String provinceName, String districtName) {

    public String description() {
        return provinceName + " " + districtName;
    }

    public String requestDescription() {
        return "brtcCode=" + provinceCode + "&signguCode=" + districtCode;
    }
}
