package com.toadzip.backend.ingest.domain;

public record JusoAddressCode(
        String administrativeCode,
        String roadNameCode,
        String underground,
        String buildingMainNumber,
        String buildingSubNumber
) {

    public JusoAddressCode {
        requireText(administrativeCode, "행정구역코드");
        requireText(roadNameCode, "도로명코드");
        requireText(underground, "지하여부");
        requireText(buildingMainNumber, "건물본번");
        buildingSubNumber = defaultSubNumber(buildingSubNumber);
    }

    private static String defaultSubNumber(String value) {
        if (value == null || value.isBlank()) {
            return "0";
        }
        return value;
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }
}
