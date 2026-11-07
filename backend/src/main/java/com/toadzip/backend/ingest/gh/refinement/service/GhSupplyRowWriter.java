package com.toadzip.backend.ingest.gh.refinement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.ComplexLinkMethod;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * GH 지구의 주택형마다 PDF 보정이 채울 공급행 틀을 만든다. 행 키는 `{공고 식별자}:{지구명}:{주택형 코드}`이고, 단지는 공고 단지가 미리
 * 고른 제품 단지, 주택형 연결은 비운다. 원천 주택형의 전용면적은 공고 면적 칸에 적어 PDF 추출이 틀 행과 맞출 때 쓴다(면적 보정은 켜지 않는다). 관리자가 PDF 보정 등으로 공고를 손댔거나(adminUpdatedAt, 휴지통에서 복구한 공고 포함),
 * 공개했거나, 공고 단지를 직접 연결한 공고는 관리자가 공급행을 맡으므로 다시 쓰지 않고, 그 뒤 원천 공급이 바뀌면 공급 재확인
 * 대상으로 표시한다. 공급행은 지구의 공고 단지를 가리켜 관리자가 공고 단지를 다시 연결하면 따라간다.
 * 원천과 같으면 아무것도 바꾸지 않고, 다르면 틀을 원천에 맞춰 다시 만든다(관리자가 손대기 전이라 지울 조건·연결이 없다).
 */
@Component
@RequiredArgsConstructor
class GhSupplyRowWriter {

    private final SupplyRowRepository supplyRows;
    private final AnnouncementComplexRepository announcementComplexes;
    private final EntityManager entityManager;

    /** 관리자가 맡은 공고의 원천 공급이 바뀌어 공급 재확인이 새로 켜졌으면 true다. */
    boolean write(Announcement announcement, String title, JsonNode districts, Map<String, AnnouncementComplex> complexes) {
        List<Line> planned = planned(announcement.getSourceAnnouncementIdentifier(), category(title), districts,
                complexes);
        boolean adminOwned = adminOwned(announcement);
        boolean recheck = announcement.observeAgencySupply(fingerprint(planned), adminOwned);
        if (adminOwned) {
            return recheck;
        }
        List<SupplyRow> stored = supplyRows.findAllByAnnouncement(announcement);
        if (stored.stream().map(Line::of).toList().equals(planned)) {
            return false;
        }
        if (onlyAreasDiffer(stored, planned)) {
            fillAreas(stored, planned);
            return false;
        }
        supplyRows.deleteAll(stored);
        entityManager.flush();
        for (Line line : planned) {
            SupplyRow row = SupplyRow.create(announcement, line.complex(), null, line.key(), line.order(),
                    line.complexName(), line.typeName(), null, null, line.category(), null, line.householdCount());
            row.fillAnnouncementComplex(line.announcementComplex());
            row.recordSourceArea(line.exclusiveArea());
            supplyRows.save(row);
        }
        return false;
    }

    /** 면적만 다르면 틀을 지우고 다시 만들지 않는다. 행 ID가 바뀌면 작업실 초안이 낡은 것으로 보인다. */
    private static boolean onlyAreasDiffer(List<SupplyRow> stored, List<Line> planned) {
        if (stored.size() != planned.size()) {
            return false;
        }
        for (int index = 0; index < stored.size(); index++) {
            if (!Line.of(stored.get(index)).withArea(planned.get(index).exclusiveArea()).equals(planned.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static void fillAreas(List<SupplyRow> stored, List<Line> planned) {
        for (int index = 0; index < stored.size(); index++) {
            stored.get(index).recordSourceArea(planned.get(index).exclusiveArea());
        }
    }

    /** 원천에서 빠진 공고 단지를 포함해 관리자가 연결한 공고 단지가 하나라도 있으면 관리자가 공급행을 맡은 것으로 본다. */
    private boolean adminOwned(Announcement announcement) {
        return announcement.getAdminUpdatedAt() != null || announcement.isPublished()
                || announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement).stream()
                        .anyMatch(complex -> complex.getLinkMethod() == ComplexLinkMethod.ADMIN);
    }

    /**
     * 원천이 정하는 값(행 키·주택형명·세대수·공급구분)의 지문. 미리 고른 단지는 관리자가 바꾸는 값이라 넣지 않는다.
     * 전용면적은 틀에 나중에 더한 값이라 넣지 않는다. 넣으면 관리자가 맡은 공고가 원천이 그대로여도 모두 공급 재확인 대상이 된다.
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
                        exclusiveArea(housingType), category, announcementComplex, announcementComplex.linkedComplex()));
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
            BigDecimal exclusiveArea, SupplyCategory category, AnnouncementComplex announcementComplex,
            HousingComplex complex) {

        static Line of(SupplyRow row) {
            return new Line(row.getSourceSupplyRowIdentifier(), row.getDisplayOrder(), row.getSourceComplexName(),
                    row.getSourceHousingTypeName(), row.getTotalSupplyHouseholdCount(), row.getAnnouncementExclusiveArea(),
                    row.getSupplyCategory(), row.getAnnouncementComplex(), row.getHousingComplex());
        }

        Line withArea(BigDecimal area) {
            return new Line(key, order, complexName, typeName, householdCount, area, category, announcementComplex,
                    complex);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Line line && key.equals(line.key) && order == line.order
                    && complexName.equals(line.complexName) && typeName.equals(line.typeName)
                    && Objects.equals(householdCount, line.householdCount) && sameArea(exclusiveArea, line.exclusiveArea)
                    && category == line.category
                    && Objects.equals(complexId(complex), complexId(line.complex))
                    && Objects.equals(announcementComplexId(announcementComplex),
                            announcementComplexId(line.announcementComplex));
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, order, complexId(complex));
        }

        /** DB는 소수 넷째 자리까지 저장해(36.8000) 값 크기로 비교한다. */
        private static boolean sameArea(BigDecimal left, BigDecimal right) {
            if (left == null || right == null) {
                return left == right;
            }
            return left.compareTo(right) == 0;
        }

        private static Long announcementComplexId(AnnouncementComplex complex) {
            if (complex == null) {
                return null;
            }
            return complex.getId();
        }

        private static Long complexId(HousingComplex complex) {
            if (complex == null) {
                return null;
            }
            return complex.getId();
        }
    }
}
