package com.toadzip.backend.ingest.domain;

import java.math.BigDecimal;

public record LocationSummaryRecord(
        String districtCode,
        String entranceSerial,
        String legalDongCode,
        String provinceName,
        String districtName,
        String townName,
        String roadNameCode,
        String roadName,
        String underground,
        int buildingMainNumber,
        int buildingSubNumber,
        BigDecimal x,
        BigDecimal y
) {

    public LocationSummaryRecord {
        requireLength(districtCode, 5, "시군구코드");
        requireText(entranceSerial, "출입구일련번호");
        requireLength(legalDongCode, 10, "법정동코드");
        requireText(provinceName, "시도명");
        requireLength(roadNameCode, 12, "도로명코드");
        requireText(roadName, "도로명");
        requireLength(underground, 1, "지하여부");
        requireNonNegative(buildingMainNumber, "건물본번");
        requireNonNegative(buildingSubNumber, "건물부번");
        requireCoordinatePair(x, y);
        if (!roadNameCode.startsWith(districtCode)) {
            throw new IllegalArgumentException("도로명코드는 시군구코드로 시작해야 합니다.");
        }
    }

    public String provinceCode() {
        return districtCode.substring(0, 2);
    }

    public String roadAddress() {
        StringBuilder address = new StringBuilder(provinceName);
        append(address, districtName);
        if (isTownOrTownship(townName)) {
            append(address, townName);
        }
        append(address, roadName);
        append(address, buildingNumber());
        return address.toString();
    }

    public String normalizedRoadAddress() {
        return new NormalizedRoadAddress(roadAddress()).withoutReference();
    }

    public boolean hasCoordinate() {
        return x != null;
    }

    private String buildingNumber() {
        String prefix = "1".equals(underground) ? "지하 " : "";
        if (buildingSubNumber == 0) {
            return prefix + buildingMainNumber;
        }
        return prefix + buildingMainNumber + "-" + buildingSubNumber;
    }

    private static boolean isTownOrTownship(String value) {
        return value != null && (value.endsWith("읍") || value.endsWith("면"));
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(' ').append(value.strip());
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }

    private static void requireLength(String value, int length, String fieldName) {
        if (value == null || value.length() != length) {
            throw new IllegalArgumentException(fieldName + " 형식이 올바르지 않습니다.");
        }
    }

    private static void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + "은 0 이상이어야 합니다.");
        }
    }

    private static void requireCoordinatePair(BigDecimal x, BigDecimal y) {
        if ((x == null) != (y == null)) {
            throw new IllegalArgumentException("X좌표와 Y좌표는 함께 제공되어야 합니다.");
        }
    }
}
