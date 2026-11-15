package com.toadzip.backend.ingest.gh.refinement.service;

import com.toadzip.backend.announcement.domain.AgencySupplyLine;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.repository.AgencySupplyLineStore;
import com.toadzip.backend.housing.domain.AgencyCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * GH 지구의 주택형마다 기관 준비 줄 하나를 쓴다({@link AgencySupplyLineStore}). 줄 키는 `{공고 식별자}:{지구명}:{주택형 코드}`이고,
 * 단지는 지구의 공고 단지다(공고 단지가 고른 GH 제품 단지를 따라간다). 공고 공급표(정본)는 쓰지 않는다. PDF 보정이 이 줄을 공급행 틀로
 * 읽고, 정본을 확정한 뒤 원천 공급이 바뀌면 공급 재확인 대상으로 표시한다.
 */
@Component
@RequiredArgsConstructor
class GhSupplyLineWriter {

    private final AgencySupplyLineStore lines;

    /** 정본을 확정한 공고의 원천 공급이 바뀌어 공급 재확인이 새로 켜졌으면 true다. */
    boolean write(Announcement announcement, String title, JsonNode districts, Map<String, AnnouncementComplex> complexes) {
        List<Line> planned = planned(announcement.getSourceAnnouncementIdentifier(), category(title), districts,
                complexes);
        lines.replace(announcement, AgencyCode.GH, planned.stream().map(Line::values).toList());
        return announcement.observeAgencySupply(fingerprint(planned));
    }

    /**
     * 원천이 정하는 값(줄 키·주택형명·세대수·공급구분)의 지문. 미리 고른 단지는 관리자가 바꾸는 값이라 넣지 않는다. 전용면적은 틀에 나중에
     * 더한 값이라 넣지 않는다. 넣으면 확정한 공고가 원천이 그대로여도 모두 공급 재확인 대상이 된다.
     */
    private static String fingerprint(List<Line> planned) {
        String source = planned.stream().map(line -> String.join("\u0001", line.key(), line.typeName(),
                String.valueOf(line.householdCount()), line.category().name())).collect(Collectors.joining("\u0002"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", exception);
        }
    }

    private List<Line> planned(String identifier, SupplyCategory category, JsonNode districts,
            Map<String, AnnouncementComplex> complexes) {
        List<Line> lines = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode district : districts) {
            String name = district.path("name").asText("").strip();
            AnnouncementComplex announcementComplex = complexes.get(name);
            if (announcementComplex == null) {
                continue;
            }
            for (JsonNode housingType : district.path("housingTypes")) {
                String typeName = housingType.path("name").asText();
                String key = uniqueKey(identifier + ":" + name + ":" + typeCode(housingType, typeName), keys);
                lines.add(new Line(key, lines.size(), name, typeName, householdCount(housingType),
                        exclusiveArea(housingType), category, announcementComplex));
            }
        }
        return lines;
    }

    /** 예비입주자 모집은 기존 단지의 빈집을 다시 공급하는 공고다(추정 규칙, PDF 보정에서 고칠 수 있다). */
    private static SupplyCategory category(String title) {
        if (title.contains("예비입주자")) {
            return SupplyCategory.RESUPPLY;
        }
        return SupplyCategory.NEW_SUPPLY;
    }

    private static String typeCode(JsonNode housingType, String typeName) {
        String code = housingType.path("houseTypeCode").asText("");
        if (code.isBlank()) {
            return typeName;
        }
        return code;
    }

    private static String uniqueKey(String key, Set<String> keys) {
        String unique = key;
        for (int suffix = 2; !keys.add(unique); suffix++) {
            unique = key + "#" + suffix;
        }
        return unique;
    }

    private static Integer householdCount(JsonNode housingType) {
        String value = housingType.path("supplyCount").asText("").replace(",", "").strip();
        if (!value.matches("\\d{1,7}")) {
            return null;
        }
        return Integer.parseInt(value);
    }

    private static BigDecimal exclusiveArea(JsonNode housingType) {
        String value = housingType.path("exclusiveArea").asText("").replace(",", "").strip();
        if (!value.matches("\\d{1,6}(\\.\\d{1,4})?")) {
            return null;
        }
        return new BigDecimal(value);
    }

    private record Line(String key, int order, String complexName, String typeName, Integer householdCount,
            BigDecimal exclusiveArea, SupplyCategory category, AnnouncementComplex announcementComplex) {

        AgencySupplyLine.Values values() {
            return new AgencySupplyLine.Values(key, order, announcementComplex, complexName, typeName, exclusiveArea,
                    null, null, householdCount, null, null, null, category);
        }
    }
}
