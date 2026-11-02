package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Row;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 공고 단지의 관리자 연결. 어느 공고 단지를 어느 단지로 연결할지 정하고(ADMIN), 그 공고 단지의 공급행을 연결 단지의 같은 전용면적 주택형으로
 * 그 자리에서 옮긴다. 정제를 기다리지 않는다. 연결이나 공급행이 바뀌면 이력을 남기고, 공급행의 이전·이후 단지와 주택형도 함께 적는다.
 * PDF·공급 보정 저장(plan → apply)과 공고 단지 연결 변경(relink)이 함께 쓴다.
 */
@Component
@RequiredArgsConstructor
class AnnouncementComplexAdminLinks {
    private final AdminDataChangeRepository changes;
    private final HousingTypeRepository types;
    private final HousingComplexRepository complexes;
    private final SupplyRowRepository rows;
    private final AnnouncementComplexRepository announcementComplexes;
    private final ObjectMapper json;
    private final Clock clock;

    /** 보정 저장이 관리자 연결로 정하는 공고 단지와 단지, 그 자리에서 같은 면적 주택형으로 맞출 단지 없는 행의 작업 키. */
    record Plan(AnnouncementComplex target, HousingComplex complex, List<String> fillRowKeys) { }

    /**
     * 보정 요청에서 바꾼 기존 행이 속한 공고 단지만 정한다. 관리자가 손대지 않은 공고 단지의 연결은 그대로 둔다.
     * 그 공고 단지에서 제외하지 않은 행이 고른 단지는 하나여야 한다. 맞출 행은 저장 전에도 요청에도 단지가 없는 행이라,
     * 관리자가 이번에 단지를 비운 행은 다시 채우지 않는다.
     */
    List<Plan> plan(Announcement announcement, List<Row> before, List<Row> planned) {
        var stored = rows.findAllByAnnouncement(announcement).stream()
                .collect(Collectors.toMap(SupplyRow::getId, Function.identity()));
        var beforeById = before.stream().collect(Collectors.toMap(Row::id, Function.identity()));
        Map<Long, AnnouncementComplex> touched = new TreeMap<>();
        for (var row : planned) {
            var saved = row.id() == null ? null : stored.get(row.id());
            if (saved != null && saved.getAnnouncementComplex() != null && !same(beforeById.get(row.id()), row)) {
                touched.put(saved.getAnnouncementComplex().getId(), saved.getAnnouncementComplex());
            }
        }
        List<Plan> result = new ArrayList<>();
        touched.forEach((targetId, target) -> {
            var members = planned.stream().filter(row -> row.id() != null && !row.excluded()
                    && stored.get(row.id()).getAnnouncementComplex() != null
                    && stored.get(row.id()).getAnnouncementComplex().getId().equals(targetId)).toList();
            var chosen = members.stream().map(Row::housingComplexId).filter(Objects::nonNull).distinct().toList();
            if (chosen.size() > 1) {
                throw new IllegalArgumentException(target.getSourceComplexName() + ": 공고문의 한 단지에 속한 공급행은 같은 단지로 "
                        + "연결하세요. 공통 모집 묶음 행의 단지는 원천별 공급행 보정에서 함께 바꾸세요.");
            }
            if (chosen.isEmpty()) { return; }
            var fill = members.stream().filter(row -> row.housingComplexId() == null && row.groupId() == null
                    && beforeById.get(row.id()).housingComplexId() == null).map(Row::key).toList();
            var complex = complexes.findById(chosen.getFirst()).filter(value -> !value.isAdminDeleted())
                    .orElseThrow(() -> new AdminDataConflictException("연결할 단지가 없거나 휴지통에 있습니다."));
            result.add(new Plan(target, complex, fill));
        });
        return result;
    }

    List<String> warnings(List<Plan> plans) {
        return plans.stream().map(plan -> plan.target().getSourceComplexName() + ": 공고 단지를 " + plan.complex().getName()
                + "에 관리자 연결합니다." + (plan.fillRowKeys().isEmpty() ? ""
                : " 단지가 없는 같은 단지 행 " + plan.fillRowKeys().size() + "개도 이 단지에 연결하고, 같은 전용면적 주택형이 하나면 그 주택형으로 맞춥니다.")).toList();
    }

    /**
     * 정한 연결을 저장하고 단지 없던 행을 연결 단지로 맞춘다. 새로 추가한 행은 공고에서 그 단지에 연결된 공고 단지가 하나일 때
     * 그 공고 단지를 가리킨다. written은 작업 키별 저장된 공급행이다.
     */
    void apply(Announcement announcement, List<Plan> plans, List<Row> planned, Map<String, SupplyRow> written, String actor) {
        for (var plan : plans) {
            link(plan.target(), plan.complex(), plan.fillRowKeys().stream().map(written::get).toList(), actor);
        }
        var all = announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement);
        for (var row : planned) {
            var saved = written.get(row.key());
            if (row.id() != null || saved.getAnnouncementComplex() != null || saved.getHousingComplex() == null) { continue; }
            var linked = all.stream().filter(target -> target.linkedComplex() != null
                    && target.linkedComplex().getId().equals(saved.getHousingComplex().getId())).toList();
            if (linked.size() == 1) { saved.fillAnnouncementComplex(linked.getFirst()); }
        }
    }

    /**
     * 공고 단지 하나의 연결 단지를 관리자가 바꾼다. 그 공고 단지의 제외하지 않은 공급행도 새 단지의 같은 전용면적 주택형으로 옮긴다.
     * 지금 연결과 같은 단지로 확인만 하면 공급행의 주택형 연결은 건드리지 않는다. 공통 모집 묶음 행이 있으면 묶음을 함께 옮길 수 없어
     * 공급정보 보정에서 바꾸게 한다.
     */
    void relink(Announcement announcement, long announcementComplexId, long housingComplexId, String actor) {
        var target = announcementComplexes.findById(announcementComplexId)
                .filter(complex -> complex.getAnnouncement().getId().equals(announcement.getId()))
                .orElseThrow(() -> new IllegalArgumentException("이 공고의 공고 단지가 아닙니다."));
        var complex = complexes.findById(housingComplexId).filter(value -> !value.isAdminDeleted())
                .orElseThrow(() -> new IllegalArgumentException("연결할 단지가 없거나 휴지통에 있습니다."));
        var members = rows.findAllByAnnouncement(announcement).stream().filter(row -> !row.isAdminExcluded()
                && row.getAnnouncementComplex() != null && row.getAnnouncementComplex().getId().equals(target.getId())).toList();
        if (members.stream().anyMatch(row -> row.getSupplyGroup() != null)) {
            throw new IllegalArgumentException(target.getSourceComplexName() + ": 공통 모집 묶음 행이 있어 여기서 단지를 바꿀 수 없습니다. "
                    + "공급정보 보정에서 묶음 행과 함께 바꾸세요.");
        }
        boolean confirmOnly = target.linkedComplex() != null && target.linkedComplex().getId().equals(complex.getId());
        link(target, complex, confirmOnly ? List.of() : members, actor);
    }

    /** 공급행 주택형은 LH 대표 전용면적(없으면 공고 면적)과 같은 주택형이 하나일 때만 정한다. */
    private void link(AnnouncementComplex target, HousingComplex complex, Collection<SupplyRow> rows, String actor) {
        String before = linkData(target, rows);
        boolean changed = target.linkByAdmin(complex, actor, clock.instant());
        for (var row : rows) {
            BigDecimal area = row.getLhExclusiveArea() != null ? row.getLhExclusiveArea() : row.effectiveExclusiveArea();
            changed |= row.relinkByAdminMatch(complex, sameAreaType(complex, area));
        }
        if (changed) {
            changes.save(new AdminDataChange("ANNOUNCEMENT_COMPLEX", target.getId(), "LINK_BY_ADMIN", actor, before, linkData(target, rows)));
        }
    }

    private String linkData(AnnouncementComplex target, Collection<SupplyRow> rows) {
        var data = new LinkedHashMap<String, Object>();
        data.put("announcementId", target.getAnnouncement().getId());
        data.put("sourceComplexName", target.getSourceComplexName());
        data.put("housingComplexId", target.getHousingComplex() == null ? null : target.getHousingComplex().getId());
        data.put("linkMethod", target.getLinkMethod());
        data.put("linkedBy", target.getLinkedBy());
        data.put("linkedAt", target.getLinkedAt());
        data.put("rows", rows.stream().map(row -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("id", row.getId());
            value.put("housingComplexId", row.getHousingComplex() == null ? null : row.getHousingComplex().getId());
            value.put("housingTypeId", row.getHousingType() == null ? null : row.getHousingType().getId());
            value.put("housingTypeIds", row.getHousingTypes().stream().map(HousingType::getId).sorted().toList());
            return value;
        }).toList());
        return json.writeValueAsString(data);
    }

    private HousingType sameAreaType(HousingComplex complex, BigDecimal exclusiveArea) {
        if (exclusiveArea == null) { return null; }
        var matches = types.findAllByHousingComplex(complex).stream()
                .filter(type -> type.getExclusiveArea() != null && type.getExclusiveArea().compareTo(exclusiveArea) == 0)
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private boolean same(Object first, Object second) { return json.valueToTree(first).equals(json.valueToTree(second)); }
}
