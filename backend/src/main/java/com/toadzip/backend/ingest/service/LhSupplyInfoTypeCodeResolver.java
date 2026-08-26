package com.toadzip.backend.ingest.service;

import java.util.Map;
import java.util.Optional;

public class LhSupplyInfoTypeCodeResolver {

    private static final Map<String, String> CODE_BY_NAME = Map.of(
            "5년임대", "060",
            "10년임대", "060",
            "50년임대", "061",
            "국민임대", "062",
            "영구임대", "062",
            "행복주택", "063",
            "통합공공임대", "064"
    );

    public Optional<String> resolve(String supplyTypeName) {
        if (supplyTypeName == null || supplyTypeName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CODE_BY_NAME.get(supplyTypeName.strip()));
    }
}
