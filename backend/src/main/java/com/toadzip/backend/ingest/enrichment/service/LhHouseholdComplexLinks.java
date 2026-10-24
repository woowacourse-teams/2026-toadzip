package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdComplexLink;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHouseholdSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 관리자가 저장한 세대수 보강 단지 연결을 원천·단지 지문으로 판정한다. */
@Component
class LhHouseholdComplexLinks {
    private final ObjectMapper json;

    LhHouseholdComplexLinks(ObjectMapper json) {
        this.json = json;
    }

    /** 저장 당시와 원천·단지가 같을 때만 연결 대상 단지를 돌려준다. 그렇지 않으면 null이다. */
    HousingComplex validTarget(LhHouseholdComplexLink link, LhHousingTypeHouseholdSource source,
            HousingComplex complex) {
        if (complex == null || !sourceFingerprint(source).equals(link.getSourceFingerprint())
                || !targetFingerprint(complex).equals(link.getTargetFingerprint())) {
            return null;
        }
        return complex;
    }

    String sourceFingerprint(LhHousingTypeHouseholdSource source) {
        return hash(source.areaName(), source.supplyTypeName(), source.complexName(), source.totalHouseholdCount());
    }

    String targetFingerprint(HousingComplex complex) {
        return hash(complex.getId(), complex.getSupplyType(), complex.getTotalHouseholdCount(),
                complex.getAddress().getRoadAddress(), complex.isAdminDeleted());
    }

    String hash(Object... values) {
        var texts = Arrays.stream(values).map(value -> Objects.toString(value, "")).toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(texts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
