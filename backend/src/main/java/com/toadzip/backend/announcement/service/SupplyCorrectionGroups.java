package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowGroup;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.NewGroup;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Row;
import com.toadzip.backend.announcement.repository.SupplyRowGroupRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSupplyRowGroups;
import com.toadzip.backend.ingest.mapping.service.AnnouncementSupplyMatchingService;
import com.toadzip.backend.ingest.mapping.service.SupplyMatchingFingerprint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 공급 보정의 새 공통 모집 묶음. 기존 행과 조건의 ID를 보존하며 같은 트랜잭션에서 연결한다. */
@Component
@RequiredArgsConstructor
class SupplyCorrectionGroups {
    private final SupplyRowRepository rows;
    private final SupplyRowGroupRepository groups;
    private final MyHomeAnnouncementSourceReader sources;
    private final SupplyMatchingFingerprint fingerprints;
    private final AnnouncementSupplyMatchingService supplyMatching;

    List<Plan> plan(Announcement announcement, List<Row> planned, List<NewGroup> requested) {
        if (requested.isEmpty()) { return List.of(); }
        var stored = rows.findAllByAnnouncement(announcement);
        var bySource = MyHomeAnnouncementSupplyRowGroups.byMyHomeSource(
                announcement.getSourceAnnouncementIdentifier(), stored);
        var byKey = planned.stream().collect(Collectors.toMap(Row::key, Function.identity()));
        var currentSources = sources.findAllByPblancIdOrderByIdAsc(announcement.getSourceAnnouncementIdentifier());
        Set<String> usedMembers = new HashSet<>();
        Set<String> usedSources = new HashSet<>();
        List<Plan> result = new ArrayList<>();
        for (var input : requested) {
            var original = stored.stream().filter(row -> row.getId().equals(input.sourceRowId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("분할할 원천 공급행이 이 공고에 없습니다."));
            // LH 공급정보로 여러 행이 된 원천은 그 LH 행 단위로 묶는다. 마이홈 원천 단위로 묶으면 같은 원천의 다른 행이 사라진다.
            var expanded = original.getSupplyGroup() == null ? supplyMatching.expandedSplitOrigin(
                    announcement.getSourceAnnouncementIdentifier(), original.getSourceSupplyRowIdentifier()).orElse(null) : null;
            if (expanded != null) {
                if (!usedSources.add(expanded.key()) || groups.findBySourceRowIdentifier(expanded.key()).isPresent()) {
                    throw new IllegalArgumentException("이미 묶인 원천이거나 중복된 분할입니다.");
                }
                validateMembers(input, original, byKey, usedMembers);
                result.add(new Plan(input, expanded.key(), expanded.fingerprint(), expanded.myHomeSourceKey()));
                continue;
            }
            var owners = bySource.entrySet().stream().filter(entry -> entry.getValue().contains(original)).toList();
            if (owners.size() != 1 || owners.getFirst().getValue().size() != 1) {
                throw new IllegalArgumentException("하나의 원천에 대응하는 단일 공급행만 새 공통 모집 묶음으로 나눌 수 있습니다.");
            }
            String sourceKey = owners.getFirst().getKey();
            if (!usedSources.add(sourceKey) || groups.findBySourceRowIdentifier(sourceKey).isPresent()) {
                throw new IllegalArgumentException("이미 묶인 원천이거나 중복된 분할입니다.");
            }
            var source = currentSources.stream().filter(value -> value.getSourceKey().equals(sourceKey) && value.isActive())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("현재 유효한 공고 원천을 확인할 수 없습니다."));
            validateMembers(input, original, byKey, usedMembers);
            result.add(new Plan(input, sourceKey, fingerprints.splitSource(source), sourceKey));
        }
        return List.copyOf(result);
    }

    private void validateMembers(NewGroup input, SupplyRow original, Map<String, Row> rows, Set<String> used) {
        String originalKey = "row:" + original.getId();
        if (!input.rowKeys().contains(originalKey)) {
            throw new IllegalArgumentException("분할할 기존 행과 그 조건을 변경안에 유지하세요.");
        }
        var anchor = rows.get(originalKey);
        Set<Long> types = new HashSet<>();
        for (String key : input.rowKeys()) {
            var member = rows.get(key);
            if (member == null || !used.add(key) || member.excluded() || member.groupId() != null
                    || member.housingTypeId() == null || member.newHousingType() != null
                    || member.householdCount() != null || !types.add(member.housingTypeId())) {
                throw new IllegalArgumentException("공통 모집 묶음에는 모집수를 중복 기입하지 않은 서로 다른 기존 주택형을 선택하세요.");
            }
            if (member.id() != null && !member.id().equals(original.getId())) {
                throw new IllegalArgumentException("다른 기존 공급행의 조건은 새 묶음으로 이동할 수 없습니다.");
            }
            if (!Objects.equals(member.housingComplexId(), anchor.housingComplexId())
                    || !Objects.equals(member.pnu(), original.getSupplyPnu())
                    || member.category() != original.getSupplyCategory()) {
                throw new IllegalArgumentException("공통 모집 묶음은 같은 단지·소재지·공급구분이어야 합니다.");
            }
        }
    }

    void apply(Announcement announcement, List<Plan> plans, Map<String, SupplyRow> members,
            SupplyCorrection.Request request, String actor) {
        for (var plan : plans) {
            var group = SupplyRowGroup.create(announcement, plan.sourceKey());
            group.confirm(plan.input().sharedHouseholdCount(), plan.sourceFingerprint(), request.sourceUrl().strip(),
                    request.reason().strip(), actor);
            groups.saveAndFlush(group);
            for (String key : plan.input().rowKeys()) {
                var member = members.get(key);
                member.protectInGroup(group, fingerprints.target(member.getHousingType()));
                member.recordMyHomeSource(plan.myHomeSourceKey());
            }
        }
    }

    record Plan(NewGroup input, String sourceKey, String sourceFingerprint, String myHomeSourceKey) { }
}
