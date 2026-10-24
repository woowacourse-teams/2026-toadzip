package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowGroup;
import com.toadzip.backend.announcement.repository.SupplyRowGroupRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSupplyRowGroups;
import com.toadzip.backend.ingest.mapping.repository.AnnouncementSupplyMatchDecisionRepository;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyMatcher.MyHomeSupplyMatchResult;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AnnouncementSupplySplitWriter {
    private final SupplyRowGroupRepository groups;
    private final SupplyRowRepository rows;
    private final SupplyTargetRepository targets;
    private final AnnouncementSupplyMatchDecisionRepository decisions;
    private final SupplyMatchingFingerprint fingerprints;

    void apply(Announcement announcement, SplitPlan plan) {
        var data = plan.source();
        String origin = SupplyRowGroupOrigins.key(data);
        var group = groups.findBySourceRowIdentifier(origin)
                .orElseGet(() -> SupplyRowGroup.create(announcement, origin));
        if (!group.getAnnouncement().getId().equals(announcement.getId())) {
            throw new AdminDataConflictException("다른 공고의 원천 보정입니다.");
        }
        var stored = rows.findAllByAnnouncement(announcement);
        var members = stored.stream().filter(row -> row.getSupplyGroup() != null
                && row.getSupplyGroup().getSourceRowIdentifier().equals(origin)).toList();
        var requestedIds = plan.targets().stream().map(SplitTarget::id)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        long requestedExisting = plan.targets().stream().filter(target -> target.id() != null).count();
        if (requestedIds.size() != requestedExisting
                || !requestedIds.equals(members.stream().map(SupplyRow::getId).collect(Collectors.toSet()))) {
            throw new AdminDataConflictException("기존 보정 공급행을 누락하거나 다른 행을 사용할 수 없습니다. 다시 확인해 주세요.");
        }
        // LH 공급정보로 펼친 원천은 나누는 그 행만 대체한다. 마이홈 원천 전체를 대체하면 같은 원천의 다른 LH 행이 사라진다.
        var replaced = plan.expanded()
                ? stored.stream().filter(row -> row.getSupplyGroup() == null
                        && row.getSourceSupplyRowIdentifier().equals(origin)).toList()
                : MyHomeAnnouncementSupplyRowGroups.byMyHomeSource(
                        announcement.getSourceAnnouncementIdentifier(), stored).getOrDefault(origin, List.of());
        if (replaced.stream().anyMatch(SupplyRow::isAdminModified)
                || !targets.findAllBySupplyRowIdIn(replaced.stream().map(SupplyRow::getId).toList()).isEmpty()) {
            throw new AdminDataConflictException("기존 공급행에 보정값 또는 대상 조건이 있습니다. 조건의 이동을 먼저 확인해 주세요.");
        }
        group.confirm(SupplyRowGroupOrigins.householdCount(data, plan.expanded()), fingerprints.splitSource(data, plan.expanded()),
                plan.sourceUrl().strip(), plan.reason().strip(), plan.actor());
        groups.saveAndFlush(group);
        int displayOrder = 1;
        for (SplitTarget target : plan.targets()) {
            applyTarget(announcement, group, data, members, target, displayOrder++, plan.expanded());
        }
        rows.deleteAll(replaced);
        decisions.findByRowIdentifier(origin).ifPresent(decisions::delete);
        rows.flush();
        announcement.recordAdminSupplyChange();
    }

    private void applyTarget(Announcement announcement, SupplyRowGroup group, MyHomeSupplyRowMappingData data,
            List<SupplyRow> members, SplitTarget target, int displayOrder, boolean expanded) {
        var match = target.match();
        var row = members.stream().filter(value -> value.getId().equals(target.id())).findFirst().orElse(null);
        if (row == null) {
            row = SupplyRow.create(announcement, match.complex(), match.housingType(), "ADMIN:" + UUID.randomUUID(),
                    displayOrder, data.sourceComplexName(), data.sourceHousingTypeName(), data.pnu(), null,
                    data.supplyCategory(), null, null);
        }
        if (row.getHousingType() != null && !row.getHousingType().getId().equals(match.housingType().getId())
                && !targets.findAllBySupplyRow(row).isEmpty()) {
            throw new AdminDataConflictException("대상 조건이 있는 공급행의 주택형은 조건과 함께 검토해 주세요.");
        }
        // 나눈 행의 이름은 원천 이름이 아니라 이 공고에서 보여 줄 표기다.
        row.reviseByAdmin(match.complex(), match.housingType(), target.name(),
                data.pnu(), row.getExpectedMoveInMonth(), data.supplyCategory(), null);
        row.updateDisplayOrder(displayOrder);
        row.protectInGroup(group, target.targetFingerprint());
        if (expanded) {
            // LH 보강이 이 행들을 나눈 LH 행의 묶음으로 알아보고 건너뛰도록 원본 LH 행을 기록한다.
            row.resolveFromLhSupply(data.resolvedLhSourceIdentifier(), null, null);
        }
        rows.save(row);
    }

    boolean reviewRequired(SupplyRowGroup group, MyHomeSupplyRowMappingData source, boolean expanded, List<SupplyRow> members) {
        return members.size() < 2 || group.isReviewRequired()
                || !fingerprints.splitSource(source, expanded).equals(group.getSourceFingerprint())
                || members.stream().anyMatch(row -> row.getHousingType() == null || row.getHousingComplex() == null
                || row.getHousingComplex().isAdminDeleted()
                || !fingerprints.target(row.getHousingType()).equals(row.getConfirmedTargetFingerprint()));
    }

    record SplitTarget(Long id, String name, MyHomeSupplyMatchResult match, String targetFingerprint) { }
    /** expanded: 원천이 LH 공급정보로 여러 행이 되어 이 행만 나누는 경우. */
    record SplitPlan(MyHomeSupplyRowMappingData source, List<SplitTarget> targets,
            String sourceUrl, String reason, String actor, boolean expanded) { }
}
