package com.toadzip.backend.ingest.mapping.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record InitialRegistrationInformationResponse(String fingerprint, String complexName,
        List<HousingType> housingTypes, Supplement supplement) {
    public record HousingType(String sourceKey, String name, BigDecimal exclusiveArea) { }
    public record Supplement(String complexName, Map<String, String> housingTypeNames, String evidence,
            String checkedBy, Instant checkedAt, boolean current) { }
}
