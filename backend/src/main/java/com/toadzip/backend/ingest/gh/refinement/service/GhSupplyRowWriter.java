package com.toadzip.backend.ingest.gh.refinement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.ComplexLinkMethod;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * GH 지구의 주택형마다 PDF 보정이 채울 공급행 틀을 만든다. 행 키는 `{공고 식별자}:{지구명}:{주택형 코드}`이고, 단지는 공고 단지가 미리
 * 고른 제품 단지, 주택형 연결은 비운다. 관리자가 PDF 보정·휴지통 이동 등으로 손댔거나(adminUpdatedAt), 공개했거나, 공고 단지를
 * 직접 연결한 공고는 관리자가 공급행을 맡으므로 다시 쓰지 않는다. 공급행은 지구의 공고 단지를 가리켜 관리자가 공고 단지를 다시
 * 연결하면 따라간다.
 * 원천과 같으면 아무것도 바꾸지 않고, 다르면 틀을 원천에 맞춰 다시 만든다(관리자가 손대기 전이라 지울 조건·연결이 없다).
 */
@Component
@RequiredArgsConstructor
class GhSupplyRowWriter {

    private final SupplyRowRepository supplyRows;
    private final EntityManager entityManager;

    void write(Announcement announcement, String title, JsonNode districts, Map<String, AnnouncementComplex> complexes) {
        if (announcement.getAdminUpdatedAt() != null || announcement.isPublished()
                || complexes.values().stream().anyMatch(complex -> complex.getLinkMethod() == ComplexLinkMethod.ADMIN)) {
            return;
        }
        List<Line> planned = planned(announcement.getSourceAnnouncementIdentifier(), category(title), districts,
                complexes);
        List<SupplyRow> stored = supplyRows.findAllByAnnouncement(announcement);
        if (stored.stream().map(Line::of).toList().equals(planned)) {
            return;
        }
        supplyRows.deleteAll(stored);
        entityManager.flush();
        for (Line line : planned) {
            SupplyRow row = SupplyRow.create(announcement, line.complex(), null, line.key(), line.order(),
                    line.complexName(), line.typeName(), null, null, line.category(), null, line.householdCount());
            row.fillAnnouncementComplex(line.announcementComplex());
            supplyRows.save(row);
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
                lines.add(new Line(key, lines.size(), name, typeName, householdCount(housingType), category,
                        announcementComplex, announcementComplex.linkedComplex()));
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

    private record Line(String key, int order, String complexName, String typeName, Integer householdCount,
            SupplyCategory category, AnnouncementComplex announcementComplex, HousingComplex complex) {

        static Line of(SupplyRow row) {
            return new Line(row.getSourceSupplyRowIdentifier(), row.getDisplayOrder(), row.getSourceComplexName(),
                    row.getSourceHousingTypeName(), row.getTotalSupplyHouseholdCount(), row.getSupplyCategory(),
                    row.getAnnouncementComplex(), row.getHousingComplex());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Line line && key.equals(line.key) && order == line.order
                    && complexName.equals(line.complexName) && typeName.equals(line.typeName)
                    && Objects.equals(householdCount, line.householdCount) && category == line.category
                    && Objects.equals(complexId(complex), complexId(line.complex))
                    && Objects.equals(announcementComplexId(announcementComplex),
                            announcementComplexId(line.announcementComplex));
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, order, complexId(complex));
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
