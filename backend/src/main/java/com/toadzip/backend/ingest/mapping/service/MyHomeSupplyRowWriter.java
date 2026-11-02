package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowGroup;
import com.toadzip.backend.announcement.repository.SupplyRowGroupRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSupplyRowGroups;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyMatcher.MyHomeSupplyMatchResult;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyMatcher.MyHomeSupplyMatchingFailureData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 마이홈 공고 한 건의 공급행을 원천 순서대로 만들거나 갱신하고, 원천에서 사라진 행을 정리한다. */
@Component
@RequiredArgsConstructor
class MyHomeSupplyRowWriter {

    private final SupplyRowRepository supplyRowRepository;
    private final SupplyTargetRepository supplyTargetRepository;
    private final MyHomeAnnouncementSupplyMatcher supplyMatcher;
    private final SavedSupplyMatches savedMatches;
    private final SupplyRowGroupRepository supplyGroups;
    private final AnnouncementSupplySplitWriter splitWriter;

    /**
     * 원천 행마다 보정 묶음 보존 → LH 해석 보존 묶음 → 단일 행 쓰기 순서로 처리하고, 원천에서 사라진 행을 정리한다.
     * 보정 묶음을 지킨 원천도 같은 원천의 다른 LH 해석 행은 보존해야 하므로 LH 해석 보존 묶음까지 확인한다.
     * 보정 묶음 구성원은 LH 해석 보존 묶음에 들지 않으므로 같은 행을 두 번 쓰지 않는다.
     */
    SupplyRowsWriteResult write(
            Announcement announcement,
            List<MyHomeSupplyRowMappingData> rows,
            boolean preserveExistingLhResolvedRows,
            Set<String> historicalSourceKeys,
            Map<String, MyHomeSupplyMatchResult> selections
    ) {
        RowsWrite write = begin(announcement, rows, historicalSourceKeys);
        for (MyHomeSupplyRowMappingData data : rows) {
            boolean keptCorrection = keepCorrectionGroup(write, data);
            if (keepLhResolvedGroup(write, announcement, data, preserveExistingLhResolvedRows, historicalSourceKeys,
                    selections) || keptCorrection) {
                continue;
            }
            writeRow(write, announcement, data, selections);
        }
        List<SupplyRow> staleRows = settleDisappearedRows(write);
        deleteStaleRows(staleRows);
        return write.result(staleRows.size());
    }

    private RowsWrite begin(
            Announcement announcement, List<MyHomeSupplyRowMappingData> rows, Set<String> historicalSourceKeys
    ) {
        Map<String, SupplyRow> storedRows = supplyRowRepository.findAllByAnnouncement(announcement)
                .stream()
                .collect(Collectors.toMap(
                        SupplyRow::getSourceSupplyRowIdentifier,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        for (var data : rows) {
            var existing = storedRows.get(data.sourceSupplyRowIdentifier());
            if (existing != null && !historicalSourceKeys.contains(data.source().getSourceKey())) {
                existing.recordMyHomeSource(data.source().getSourceKey());
            }
        }
        Map<String, List<SupplyRow>> lhGroups = MyHomeAnnouncementSupplyRowGroups.byMyHomeSource(
                announcement.getSourceAnnouncementIdentifier(), storedRows.values()
        );
        Set<String> currentRowIdentifiers = rows.stream()
                .filter(row -> !historicalSourceKeys.contains(row.source().getSourceKey()))
                .map(MyHomeSupplyRowMappingData::sourceSupplyRowIdentifier)
                .collect(Collectors.toSet());
        Map<String, SupplyRowGroup> correctionGroups = supplyGroups.findAllByAnnouncement(announcement).stream()
                .collect(Collectors.toMap(SupplyRowGroup::getSourceRowIdentifier, Function.identity()));
        return new RowsWrite(storedRows, lhGroups, currentRowIdentifiers, correctionGroups,
                SupplyRowGroupOrigins.expandedIn(rows));
    }

    /** 관리자가 나눈 공통 모집 묶음은 구성원을 그대로 두고 순서만 이어 붙인다. 원천이 바뀌었으면 재확인으로 남긴다. */
    private boolean keepCorrectionGroup(RowsWrite write, MyHomeSupplyRowMappingData data) {
        var group = write.correctionGroups.get(SupplyRowGroupOrigins.key(data));
        if (group == null) {
            return false;
        }
        if (!write.keptCorrectionGroups.add(group.getId())) {
            return true;
        }
        var members = write.storedRows.values().stream().filter(row -> row.getSupplyGroup() != null
                && row.getSupplyGroup().getId().equals(group.getId()))
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId))
                .toList();
        if (splitWriter.reviewRequired(group, data, write.expanded.apply(data), members)) {
            group.requireReview();
            write.failures.add(new MyHomeSupplyMatchingFailureData(data.source(),
                    MyHomeAnnouncementMappingFailureReason.MANUAL_MATCH_REVIEW_REQUIRED,
                    "보정한 공급행의 원천 또는 연결 대상이 변경되었습니다. 기존 보정은 유지하며 다시 확인해야 합니다."));
        }
        for (SupplyRow member : members) {
            write.storedRows.remove(member.getSourceSupplyRowIdentifier());
            write.placeUnchanged(member);
        }
        return true;
    }

    /** 보존해야 하는 원천의 LH 해석 행은 연결과 주택형 표기를 그대로 두고 마이홈 원천값만 갱신한다. */
    private boolean keepLhResolvedGroup(
            RowsWrite write,
            Announcement announcement,
            MyHomeSupplyRowMappingData data,
            boolean preserveExistingLhResolvedRows,
            Set<String> historicalSourceKeys,
            Map<String, MyHomeSupplyMatchResult> selections
    ) {
        List<SupplyRow> storedGroup = write.lhGroups.get(data.sourceSupplyRowIdentifier());
        boolean historical = historicalSourceKeys.contains(data.source().getSourceKey());
        boolean preserveLhRows = preserveExistingLhResolvedRows
                || announcement.getProvider() == AgencyCode.LH && historical;
        if (!preserveLhRows || !hasLhResolvedRows(announcement, storedGroup)) {
            return false;
        }
        if (selections.containsKey(data.sourceSupplyRowIdentifier())) {
            throw new MyHomeAnnouncementMappingRejectedException(
                    MyHomeAnnouncementMappingFailureReason.INVALID_VALUE,
                    "기존 LH 공급행을 보존해야 하는 원천입니다. 최신 공급 정보를 수집한 뒤 다시 정제해 주세요.");
        }
        for (SupplyRow stored : storedGroup) {
            if (historical && write.currentRowIdentifiers.contains(stored.getSourceSupplyRowIdentifier())) {
                continue;
            }
            write.storedRows.remove(stored.getSourceSupplyRowIdentifier());
            Integer householdCount = stored.getTotalSupplyHouseholdCount();
            if (stored.getSourceSupplyRowIdentifier().equals(data.sourceSupplyRowIdentifier())) {
                householdCount = data.totalSupplyHouseholdCount();
            }
            write.count(updatePreservingLhResolution(stored, data, write.nextOrder(), householdCount));
        }
        return true;
    }

    /**
     * 원천 행 하나를 관리자 제외 → 재확인 대기 → 생성 → 이전 LH 해석 보존 → 갱신 순서로 판정해 쓴다.
     * 관리자 제외와 재확인 대기 행은 마지막 정상 연결과 모집조건을 유지하고 순서만 옮긴다.
     */
    private void writeRow(
            RowsWrite write,
            Announcement announcement,
            MyHomeSupplyRowMappingData data,
            Map<String, MyHomeSupplyMatchResult> selections
    ) {
        SupplyRow stored = write.storedRows.remove(data.sourceSupplyRowIdentifier());
        MyHomeSupplyMatchResult match = selections.get(data.sourceSupplyRowIdentifier());
        if (match == null) {
            match = supplyMatcher.match(data);
        }
        if (stored != null && stored.isAdminExcluded() && match.failure() != null) {
            // 관리자가 등록 단지에 없다고 제외한 행은 매칭 실패로 남기지 않고 그대로 둔다. LH 보강도 보류하지 않는다.
            write.place(stored);
            return;
        }
        if (match.failure() != null) {
            write.failures.add(match.failure());
        }
        if (stored != null && match.failure() != null
                && match.failure().reason() == MyHomeAnnouncementMappingFailureReason.MANUAL_MATCH_REVIEW_REQUIRED) {
            write.place(stored);
            return;
        }
        if (stored == null) {
            SupplyRow createdRow = createSupplyRow(announcement, data, match, write.nextOrder());
            createdRow.recordMyHomeSource(data.source().getSourceKey());
            applyLhResolution(createdRow, data, match);
            supplyRowRepository.save(createdRow);
            write.created++;
            return;
        }
        if (shouldPreservePreviousLhResolution(stored, data, match)) {
            write.count(updatePreservingLhResolution(stored, data, write.nextOrder(), data.totalSupplyHouseholdCount()));
            return;
        }
        update(write, stored, data, match, selections);
    }

    private void update(
            RowsWrite write,
            SupplyRow stored,
            MyHomeSupplyRowMappingData data,
            MyHomeSupplyMatchResult match,
            Map<String, MyHomeSupplyMatchResult> selections
    ) {
        Long previousHousingTypeId = housingTypeId(stored);
        boolean changed = stored.updateFromMyHome(
                match.complex(),
                match.housingType(),
                write.nextOrder(),
                data.sourceComplexName(),
                data.sourceHousingTypeName(),
                data.pnu(),
                data.supplyCategory(),
                match.failureDetail(),
                data.totalSupplyHouseholdCount()
        );
        changed |= applyLhResolution(stored, data, match);
        // 관리자 보정은 표기값만 보호한다. 연결은 저장한 매칭이나 이번 선택이 있을 때 그 값을 따른다.
        if (stored.isAdminModified() && match.failure() == null
                && (selections.containsKey(data.sourceSupplyRowIdentifier())
                || savedMatches.exists(data.sourceSupplyRowIdentifier()))) {
            changed |= stored.relinkByAdminMatch(match.complex(), match.housingType());
        }
        // 이전 주택형의 금액이 새 주택형에 남지 않게 보호한다. 미연결이던 행은 이어질 이전 금액이 없다.
        if (previousHousingTypeId != null && !Objects.equals(previousHousingTypeId, housingTypeId(stored))) {
            write.changedHousingTypeRowIds.add(stored.getId());
        }
        write.count(changed);
    }

    /**
     * 원천에서 사라진 행 가운데 보정 묶음은 재확인으로, 수동 연결은 현재 원천행 뒤에 남긴다. 나머지는 지울 행으로 돌려준다.
     */
    private List<SupplyRow> settleDisappearedRows(RowsWrite write) {
        List<SupplyRow> staleRows = new ArrayList<>();
        for (SupplyRow stored : write.storedRows.values()) {
            if (stored.getSupplyGroup() != null) {
                stored.getSupplyGroup().requireReview();
                write.placeUnchanged(stored);
                continue;
            }
            if (!stored.isAdminModified()
                    && !savedMatches.exists(stored.getSourceSupplyRowIdentifier())) {
                staleRows.add(stored);
                continue;
            }
            write.place(stored);
        }
        return staleRows;
    }

    private boolean updatePreservingLhResolution(
            SupplyRow stored,
            MyHomeSupplyRowMappingData data,
            int displayOrder,
            Integer householdCount
    ) {
        return stored.updateFromMyHome(
                stored.getHousingComplex(),
                stored.getHousingType(),
                displayOrder,
                data.sourceComplexName(),
                stored.getSourceHousingTypeName(),
                data.pnu(),
                data.supplyCategory(),
                stored.getMatchingFailureReason(),
                householdCount
        );
    }

    private Long housingTypeId(SupplyRow row) {
        if (row.getHousingType() == null) {
            return null;
        }
        return row.getHousingType().getId();
    }

    private boolean applyLhResolution(
            SupplyRow row,
            MyHomeSupplyRowMappingData data,
            MyHomeSupplyMatchResult match
    ) {
        if (data.resolvedLhSourceIdentifier() == null || match.failure() != null) {
            return false;
        }
        return row.resolveFromLhSupply(
                data.resolvedLhSourceIdentifier(),
                data.totalSupplyHouseholdCount(),
                data.lhTotalSupplyHouseholdCount()
        );
    }

    private boolean shouldPreservePreviousLhResolution(
            SupplyRow stored,
            MyHomeSupplyRowMappingData data,
            MyHomeSupplyMatchResult match
    ) {
        return match.failure() != null
                && data.resolvedLhSourceIdentifier() != null
                && stored.getLhSourceSupplyRowIdentifier() != null;
    }

    private boolean hasLhResolvedRows(Announcement announcement, List<SupplyRow> rows) {
        if (rows == null) {
            return false;
        }
        return rows.stream().anyMatch(row -> row.getLhSourceSupplyRowIdentifier() != null
                || LhSourceIdentifier.isSupplyRowOf(
                        announcement.getSourceAnnouncementIdentifier(), row.getSourceSupplyRowIdentifier()));
    }

    private void deleteStaleRows(List<SupplyRow> staleRows) {
        if (staleRows.isEmpty()) {
            return;
        }
        supplyTargetRepository.deleteAllBySupplyRowIn(staleRows);
        supplyRowRepository.deleteAll(staleRows);
    }

    private SupplyRow createSupplyRow(
            Announcement announcement,
            MyHomeSupplyRowMappingData data,
            MyHomeSupplyMatchResult match,
            int displayOrder
    ) {
        return SupplyRow.create(
                announcement,
                match.complex(),
                match.housingType(),
                data.sourceSupplyRowIdentifier(),
                displayOrder,
                data.sourceComplexName(),
                data.sourceHousingTypeName(),
                data.pnu(),
                null,
                data.supplyCategory(),
                match.failureDetail(),
                data.totalSupplyHouseholdCount()
        );
    }

    /** 공고 한 건을 쓰는 동안의 남은 저장행, 표시 순서와 집계를 담는다. */
    private static final class RowsWrite {

        private final Map<String, SupplyRow> storedRows;
        private final Map<String, List<SupplyRow>> lhGroups;
        private final Set<String> currentRowIdentifiers;
        private final Map<String, SupplyRowGroup> correctionGroups;
        private final Function<MyHomeSupplyRowMappingData, Boolean> expanded;
        private final Set<Long> keptCorrectionGroups = new HashSet<>();
        private final List<MyHomeSupplyMatchingFailureData> failures = new ArrayList<>();
        private final Set<Long> changedHousingTypeRowIds = new HashSet<>();
        private int displayOrder = 1;
        private int created;
        private int updated;
        private int unchanged;

        private RowsWrite(
                Map<String, SupplyRow> storedRows,
                Map<String, List<SupplyRow>> lhGroups,
                Set<String> currentRowIdentifiers,
                Map<String, SupplyRowGroup> correctionGroups,
                Function<MyHomeSupplyRowMappingData, Boolean> expanded
        ) {
            this.storedRows = storedRows;
            this.lhGroups = lhGroups;
            this.currentRowIdentifiers = currentRowIdentifiers;
            this.correctionGroups = correctionGroups;
            this.expanded = expanded;
        }

        private int nextOrder() {
            return displayOrder++;
        }

        /** 순서만 옮기고, 순서가 바뀌었으면 갱신으로 센다. */
        private void place(SupplyRow row) {
            count(row.updateDisplayOrder(nextOrder()));
        }

        /** 순서만 옮기고 변경 없음으로 센다. */
        private void placeUnchanged(SupplyRow row) {
            row.updateDisplayOrder(nextOrder());
            unchanged++;
        }

        private void count(boolean changed) {
            if (changed) {
                updated++;
                return;
            }
            unchanged++;
        }

        private SupplyRowsWriteResult result(int deleted) {
            return new SupplyRowsWriteResult(created, updated, unchanged, deleted, failures, changedHousingTypeRowIds);
        }
    }

    record SupplyRowsWriteResult(
            int created,
            int updated,
            int unchanged,
            int deleted,
            List<MyHomeSupplyMatchingFailureData> failures,
            Set<Long> changedHousingTypeRowIds
    ) {
    }
}
