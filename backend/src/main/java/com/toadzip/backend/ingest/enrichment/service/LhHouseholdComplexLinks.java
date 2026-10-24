package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdComplexLink;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHouseholdSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
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

    /** 같은 단지를 두고 경쟁하는 LH 묶음 전체의 구성과 세대수 값이다. 묶음이 늘거나 줄거나 값이 바뀌면 달라진다. */
    String sourcesFingerprint(List<LhHousingTypeHouseholdEnrichmentService.MatchedSource> group) {
        return hash(group.stream().sorted(Comparator.comparing(matched -> matched.sourceKey().failureKey()))
                .map(matched -> matched.sourceKey().failureKey() + "|" + sourceFingerprint(matched.source())
                        + "|" + householdCounts(matched.source()))
                .toArray());
    }

    /** 단지 결정이 기대한 단지·주택형 면적이다. 면적이 바뀌면 어떤 주택형에 세대수가 들어갈지가 달라진다. */
    String decisionTargetFingerprint(HousingComplex complex, List<HousingType> housingTypes) {
        return hash(targetFingerprint(complex), housingTypes.stream().map(HousingType::getExclusiveArea)
                .map(area -> area.stripTrailingZeros().toPlainString()).sorted().toList());
    }

    /** 경쟁 묶음들의 전체 세대수와 전용면적별 세대수가 모두 같으면 같은 공급으로 본다. */
    boolean identical(List<LhHousingTypeHouseholdEnrichmentService.MatchedSource> group) {
        return group.stream().map(matched -> matched.source().totalHouseholdCount() + "|"
                + householdCounts(matched.source())).distinct().count() == 1;
    }

    private String householdCounts(LhHousingTypeHouseholdSource source) {
        return source.housingTypes().stream()
                .map(type -> type.exclusiveArea().stripTrailingZeros().toPlainString() + ":" + type.totalHouseholdCount())
                .sorted().toList().toString();
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
