package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 공고 단지의 관리자 연결. 정한 단지로 공고 단지를 연결하고(ADMIN), 넘겨받은 공급행을 그 단지의 같은 전용면적 주택형으로 그 자리에서 옮긴다.
 * 정제를 기다리지 않는다. 연결이나 공급행이 바뀌면 이력을 남기고, 공급행의 이전·이후 단지와 주택형도 함께 적는다.
 * PDF·공급 보정과 공고 단지 연결 변경이 함께 쓴다.
 */
@Component
@RequiredArgsConstructor
class AnnouncementComplexAdminLinks {
    private final AdminDataChangeRepository changes;
    private final HousingTypeRepository types;
    private final ObjectMapper json;
    private final Clock clock;

    /** 연결이나 공급행이 하나라도 바뀌었으면 true다. 공급행 주택형은 LH 대표 전용면적(없으면 공고 면적)과 같은 주택형이 하나일 때만 정한다. */
    boolean link(AnnouncementComplex target, HousingComplex complex, Collection<SupplyRow> rows, String actor) {
        String before = linkData(target, rows);
        boolean changed = target.linkByAdmin(complex, actor, clock.instant());
        for (var row : rows) {
            BigDecimal area = row.getLhExclusiveArea() != null ? row.getLhExclusiveArea() : row.effectiveExclusiveArea();
            changed |= row.relinkByAdminMatch(complex, sameAreaType(complex, area));
        }
        if (changed) {
            changes.save(new AdminDataChange("ANNOUNCEMENT_COMPLEX", target.getId(), "LINK_BY_ADMIN", actor, before, linkData(target, rows)));
        }
        return changed;
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
}
