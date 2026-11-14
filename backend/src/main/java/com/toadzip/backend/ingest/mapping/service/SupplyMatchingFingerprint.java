package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.HousingType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 관리자 주택형 후보 응답의 token(단지·주택형의 지금 값 지문)을 만든다. */
@Component
public class SupplyMatchingFingerprint {
    private final ObjectMapper json;

    SupplyMatchingFingerprint(ObjectMapper json) {
        this.json = json;
    }

    public String target(HousingType type) {
        var complex = type.getHousingComplex();
        return hash(List.of(complex.getId(), complex.isAdminDeleted(), complex.getName(),
                text(complex.getAddress().getPnu()), complex.getSupplyType(), type.getId(), type.getName(),
                type.getExclusiveArea(), text(type.getSupplyArea())));
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString();
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
