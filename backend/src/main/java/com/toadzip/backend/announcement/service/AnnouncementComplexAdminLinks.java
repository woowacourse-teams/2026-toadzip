package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.correction.SupplySheetView;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.HousingTypeAreas;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 공고 단지의 관리자 연결. 어느 공고 단지를 어느 단지로 연결할지 정하고(ADMIN), 그 공고 단지의 주택형(공급행)을 연결 단지의 같은 전용면적 주택형으로
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

    /** 보정 저장이 관리자 연결로 정하는 공고 단지와 단지, 그 자리에서 같은 면적 주택형으로 맞출 단지 없는 주택형의 저장 key. */
    record Plan(AnnouncementComplex target, HousingComplex complex, List<String> fillKeys) { }

    /**
     * 보정 요청에서 손댄 기존 주택형(changedIds: 주택형 값이나 그 칸의 수·임대조건을 바꾼 주택형)이 속한 공고 단지만 정한다.
     * 관리자가 손대지 않은 공고 단지의 연결은 그대로 둔다.
     * 그 공고 단지의 주택형이 고른 단지는 하나여야 한다. 맞출 주택형은 저장 전에도 요청에도 단지가 없는 주택형이라,
     * 관리자가 이번에 단지를 비운 주택형은 다시 채우지 않는다.
     */
    List<Plan> plan(Announcement announcement, List<SupplySheetView.HousingType> before,
            List<SupplySheetView.HousingType> after, Set<Long> changedIds) {
        var beforeById = before.stream().collect(Collectors.toMap(SupplySheetView.HousingType::id, Function.identity()));
        var ownComplexes = announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement).stream()
                .collect(Collectors.toMap(AnnouncementComplex::getId, Function.identity()));
        Map<Long, AnnouncementComplex> touched = new TreeMap<>();
        for (var type : after) {
            var previous = type.id() == null ? null : beforeById.get(type.id());
            if (previous != null && previous.announcementComplexId() != null && changedIds.contains(type.id())) {
                touched.put(previous.announcementComplexId(), ownComplexes.get(previous.announcementComplexId()));
            }
        }
        List<Plan> result = new ArrayList<>();
        touched.forEach((targetId, target) -> {
            var members = after.stream().filter(type -> type.id() != null && beforeById.containsKey(type.id())
                    && targetId.equals(beforeById.get(type.id()).announcementComplexId())).toList();
            var chosen = members.stream().map(SupplySheetView.HousingType::housingComplexId).filter(Objects::nonNull)
                    .distinct().toList();
            if (chosen.size() > 1) {
                throw new IllegalArgumentException(target.getSourceComplexName() + ": 공고문의 한 단지에 속한 주택형은 같은 단지로 연결하세요.");
            }
            if (chosen.isEmpty()) { return; }
            var fill = members.stream().filter(type -> type.housingComplexId() == null
                    && beforeById.get(type.id()).housingComplexId() == null).map(SupplySheetView.HousingType::key).toList();
            var complex = complexes.findById(chosen.getFirst()).filter(value -> !value.isAdminDeleted())
                    .orElseThrow(() -> new AdminDataConflictException("연결할 단지가 없거나 휴지통에 있습니다."));
            result.add(new Plan(target, complex, fill));
        });
        return result;
    }

    List<String> warnings(List<Plan> plans) {
        return plans.stream().map(plan -> plan.target().getSourceComplexName() + ": 공고 단지를 " + plan.complex().getName()
                + "에 관리자 연결합니다." + (plan.fillKeys().isEmpty() ? ""
                : " 단지가 없는 같은 단지 주택형 " + plan.fillKeys().size() + "개도 이 단지에 연결하고, 같은 전용면적 주택형이 하나면 그 주택형으로 맞춥니다.")).toList();
    }

    /** 정한 연결을 저장하고 단지 없던 주택형을 연결 단지로 맞춘다. written은 저장 key별 저장된 공급행이다. */
    void apply(List<Plan> plans, Map<String, SupplyRow> written, String actor) {
        for (var plan : plans) {
            link(plan.target(), plan.complex(), plan.fillKeys().stream().map(written::get).toList(), actor);
        }
    }

    /**
     * 공고 단지가 없는 정본 행이 생기지 않게 저장할 주택형마다 공고 단지를 정한다. 공고 단지를 고르지 않은 주택형은 (공고, 단지명)의
     * 공고 단지를 찾거나 만든다. 주택형이 고른 단지가 있고 그 공고 단지가 아직 단지에 이어지지 않았으면 그 단지로 관리자 연결한다.
     * 이번 관리자 연결(plan.links)이 정하는 공고 단지는 {@link #apply}가 잇는다. 돌려주는 값은 주택형 key별 공고 단지 ID다. 고른 단지와
     * 다른 단지에 이어진 공고 단지는 계획({@link SupplySheetPlanner})이 막는다.
     */
    Map<String, Long> ensure(Announcement announcement, SupplySheetPlan plan, String actor) {
        var own = new ArrayList<>(announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement));
        var planned = plan.links().stream().map(link -> link.target().getId()).collect(Collectors.toSet());
        Map<String, Long> result = new LinkedHashMap<>();
        for (var type : plan.after().housingTypes()) {
            var target = type.announcementComplexId() == null
                    ? findOrCreate(announcement, own, type.complexName())
                    : own.stream().filter(complex -> complex.getId().equals(type.announcementComplexId())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(type.name() + ": 이 공고의 공고 단지가 아닙니다."));
            if (type.housingComplexId() != null && target.linkedComplex() == null
                    && !planned.contains(target.getId())) {
                link(target, complexes.findById(type.housingComplexId()).orElseThrow(), List.of(), actor);
            }
            result.put(type.key(), target.getId());
        }
        return result;
    }

    /**
     * 이번 저장에서 관리자가 공고 단지의 주택형을 모두 단지 없이 두면 그 공고 단지의 단지 연결을 푼다(관리자 결정으로 남겨 정제가 다시
     * 잇지 않는다). 저장 전에 단지가 있던 주택형이 하나라도 있던 공고 단지만 본다. 처음부터 단지 없던 주택형만 있는 공고 단지는 관리자가
     * 비운 것이 아니라서 건드리지 않는다. announcementComplexIds는 {@link #ensure}가 정한 주택형 key별 공고 단지 ID다.
     */
    void unlinkEmptied(Announcement announcement, SupplySheetPlan plan, Map<String, Long> announcementComplexIds,
            String actor) {
        Set<Long> linkedBefore = plan.before().housingTypes().stream()
                .filter(type -> type.housingComplexId() != null && type.announcementComplexId() != null)
                .map(SupplySheetView.HousingType::announcementComplexId).collect(Collectors.toSet());
        Map<Long, Boolean> anyLinkedType = new LinkedHashMap<>();
        plan.after().housingTypes().forEach(type -> anyLinkedType.merge(announcementComplexIds.get(type.key()),
                type.housingComplexId() != null, Boolean::logicalOr));
        for (var target : announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement)) {
            if (target.getHousingComplex() == null || !linkedBefore.contains(target.getId())
                    || !Boolean.FALSE.equals(anyLinkedType.get(target.getId()))) {
                continue;
            }
            String before = linkData(target, List.of());
            if (target.unlinkByAdmin(actor, clock.instant())) {
                changes.save(new AdminDataChange("ANNOUNCEMENT_COMPLEX", target.getId(), "UNLINK_BY_ADMIN", actor, before,
                        linkData(target, List.of())));
            }
        }
    }

    /**
     * 관리자 등록·JSON 등록의 공급행이 가리킬 공고 단지. (공고, 단지명)의 공고 단지를 찾거나 만들고, 고른 단지가 있으면 그 단지로
     * 관리자 연결한다. 이미 다른 단지에 이어진 공고 단지면 거절한다.
     */
    AnnouncementComplex declare(Announcement announcement, String complexName, HousingComplex complex, String actor) {
        var target = findOrCreate(announcement,
                new ArrayList<>(announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement)), complexName);
        if (complex == null) { return target; }
        if (target.linkedComplex() != null && !target.linkedComplex().getId().equals(complex.getId())) {
            throw new IllegalArgumentException(complexName.strip() + ": 공고문의 한 단지에 속한 주택형은 같은 단지로 연결하세요.");
        }
        if (target.linkedComplex() == null) { link(target, complex, List.of(), actor); }
        return target;
    }

    /** (공고, 단지명)의 공고 단지. 없으면 만들어 own에 더한다. */
    private AnnouncementComplex findOrCreate(Announcement announcement, List<AnnouncementComplex> own,
            String complexName) {
        String name = complexName.strip();
        var found = own.stream().filter(complex -> complex.getSourceComplexName().strip().equals(name)).findFirst();
        if (found.isPresent()) { return found.get(); }
        var created = announcementComplexes.save(AnnouncementComplex.observe(announcement, name, null, null));
        own.add(created);
        return created;
    }

    /**
     * 공고 단지 하나의 연결 단지를 관리자가 바꾼다. 그 공고 단지의 제외하지 않은 공급행도 새 단지의 같은 전용면적 주택형으로 옮긴다.
     * 지금 연결과 같은 단지로 확인만 하면 공급행의 주택형 연결은 건드리지 않는다.
     */
    void relink(Announcement announcement, long announcementComplexId, long housingComplexId, String actor) {
        var target = announcementComplexes.findById(announcementComplexId)
                .filter(complex -> complex.getAnnouncement().getId().equals(announcement.getId()))
                .orElseThrow(() -> new IllegalArgumentException("이 공고의 공고 단지가 아닙니다."));
        var complex = complexes.findById(housingComplexId).filter(value -> !value.isAdminDeleted())
                .orElseThrow(() -> new IllegalArgumentException("연결할 단지가 없거나 휴지통에 있습니다."));
        var members = rows.findAllByAnnouncement(announcement).stream().filter(row -> !row.isAdminExcluded()
                && row.getAnnouncementComplex() != null && row.getAnnouncementComplex().getId().equals(target.getId())).toList();
        boolean confirmOnly = target.linkedComplex() != null && target.linkedComplex().getId().equals(complex.getId());
        link(target, complex, confirmOnly ? List.of() : members, actor);
    }

    /** 공급행 주택형은 LH 대표 전용면적(없으면 공고 면적)으로 새 단지에서 {@link HousingTypeAreas#pick}으로 고른다. */
    private void link(AnnouncementComplex target, HousingComplex complex, Collection<SupplyRow> rows, String actor) {
        String before = linkData(target, rows);
        boolean changed = target.linkByAdmin(complex, actor, clock.instant());
        var candidates = types.findAllByHousingComplex(complex);
        for (var row : rows) {
            BigDecimal area = row.getLhExclusiveArea() != null ? row.getLhExclusiveArea() : row.effectiveExclusiveArea();
            HousingType type = HousingTypeAreas.pick(candidates, area, row.displayHousingTypeName()).orElse(null);
            changed |= row.relinkByAdminMatch(complex, type);
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
            return value;
        }).toList());
        return json.writeValueAsString(data);
    }
}
