package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.ingest.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementSupplyMatcher.LhSupplyMatchResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LH 공급 원천을 공고의 공급행에 매칭해 입주예정월·공급세대수와 임대금액 공급대상을 쓴다. 금액이 빠지거나 매칭이
 * 일부 실패하거나 공급 원천이 비면 마지막 금액을 지우지 않고 보존 사유를 남긴다. 공급이 없다고 확인된
 * 원천일 때만 보강의 금액을 지운다. 관리자가 고친 금액은 그대로 둔다.
 */
@Component
@RequiredArgsConstructor
class LhSupplyAmountWriter {

    private static final String ALL_TARGET = "전체";
    private static final String ALL_RANK = "전체";

    private final SupplyRowRepository supplyRowRepository;
    private final LhEnrichmentSupplyRows supplyRows;
    private final SupplyTargetRepository supplyTargetRepository;
    private final LhAnnouncementSupplyMatcher supplyMatcher;

    /**
     * 주택형이 바뀐 공급행({@code changedHousingTypeRowIds})은 새 금액이 있어야 한다. 없으면 거절해 이전 주택형의
     * 금액을 유지한다. LH 보강에서 뺀 마이홈 원천({@code sourceKeysExcludedFromLhEnrichment})의 공급행은 보지 않는다.
     */
    Result write(Announcement announcement, LhAnnouncementEnrichmentData data, Set<Long> changedHousingTypeRowIds,
            Set<String> sourceKeysExcludedFromLhEnrichment) {
        LhEnrichmentSupplyRows.Rows enrichmentRows = supplyRows.read(announcement, sourceKeysExcludedFromLhEnrichment);
        List<SupplyRow> rows = enrichmentRows.matchable();
        var supplies = data.supplies().stream().filter(source -> !supplyRows.skips(enrichmentRows, source)).toList();
        if (supplies.isEmpty()) {
            if (!changedHousingTypeRowIds.isEmpty()) {
                throw missingAmountForChangedHousingType();
            }
            Map<Long, List<SupplyTarget>> targets = targetsByRow(rows);
            if (data.verifiedEmptySupply()) {
                targets.values().forEach(stored -> deleteOtherLhTargets(stored, ""));
            }
            else {
                targets.values().stream().flatMap(List::stream)
                        .forEach(target -> markPreserved(target, "LH_SUPPLY_NOT_PROVIDED"));
            }
            return new Result(0, 0, 0, List.of());
        }
        SupplyMatches matches = matchSupplies(rows, supplies);
        requireAmountsForChangedHousingTypes(changedHousingTypeRowIds, matches);
        return writeMatchedSupplies(rows, matches, data.panId());
    }

    /** 공고가 LH 소유에서 벗어나면 보강의 공급대상을 모두 지운다. */
    void release(Announcement announcement) {
        List<Long> supplyRowIds = supplyRowRepository.findAllByAnnouncement(announcement).stream()
                .map(SupplyRow::getId)
                .toList();
        if (supplyRowIds.isEmpty()) {
            return;
        }
        supplyTargetRepository.deleteAll(supplyTargetRepository.findAllBySupplyRowIdIn(supplyRowIds).stream()
                .filter(target -> LhOwnedValues.owned(target.getSourceSupplyTargetIdentifier(),
                        target.isAdminModified()))
                .toList());
    }

    /**
     * LH 공급 연결이 다른 원천행으로 옮겨 간 LH 행({@code movedLhRows})을 기록한 공급행 가운데 그 LH 행이 이제 다른
     * 원천행으로 해석되는 행에서 LH 연결 기록을 놓고, 그 행에 붙은 그 LH 행의 금액을 지운다. 관리자가 고친 금액은 둔다.
     * {@code resolvedSourceRows}는 LH 행마다 지금 해석되는 마이홈 원천행이다.
     */
    void releaseMovedLhRows(Announcement announcement, Map<String, String> resolvedSourceRows, Set<String> movedLhRows) {
        Map<Long, String> movedTargets = new HashMap<>();
        for (SupplyRow row : supplyRowRepository.findAllByAnnouncement(announcement)) {
            String lhSource = row.getLhSourceSupplyRowIdentifier();
            if (movedLhRows.contains(lhSource)
                    && row.releaseLhSourceResolvedElsewhere(resolvedSourceRows.get(lhSource))) {
                movedTargets.put(row.getId(), LhSourceIdentifier.target(lhSource));
            }
        }
        if (movedTargets.isEmpty()) {
            return;
        }
        supplyTargetRepository.deleteAll(supplyTargetRepository.findAllBySupplyRowIdIn(movedTargets.keySet()).stream()
                .filter(target -> LhOwnedValues.owned(target.getSourceSupplyTargetIdentifier(), target.isAdminModified()))
                .filter(target -> movedTargets.get(target.getSupplyRow().getId())
                        .equals(target.getSourceSupplyTargetIdentifier()))
                .toList());
    }

    private void requireAmountsForChangedHousingTypes(
            Set<Long> changedHousingTypeRowIds,
            SupplyMatches matches
    ) {
        if (changedHousingTypeRowIds.isEmpty()) {
            return;
        }
        Set<Long> rowsWithAmount = matches.supplies().stream()
                .filter(matched -> hasCompleteAmount(matched.source()))
                .map(matched -> matched.row().getId())
                .collect(Collectors.toSet());
        if (!rowsWithAmount.containsAll(changedHousingTypeRowIds)) {
            throw missingAmountForChangedHousingType();
        }
    }

    private SupplyMatches matchSupplies(List<SupplyRow> rows, List<LhSupplyData> supplies) {
        List<LhSupplyMatchingFailureData> failures = new ArrayList<>();
        List<MatchedSupply> matchedSupplies = new ArrayList<>();
        Set<Long> matchedRowIds = new HashSet<>();
        for (LhSupplyData source : supplies) {
            LhSupplyMatchResult match = supplyMatcher.match(rows, source);
            if (match.failure() != null) {
                failures.add(match.failure());
                continue;
            }
            SupplyRow row = match.row();
            if (!matchedRowIds.add(row.getId())) {
                throw new LhAnnouncementEnrichmentRejectedException(
                        LhAnnouncementEnrichmentFailureReason.AMBIGUOUS_HOUSING_TYPE,
                        "여러 LH 공급행이 하나의 제품 공급행에 연결됩니다."
                );
            }
            matchedSupplies.add(new MatchedSupply(source, row));
        }
        return new SupplyMatches(matchedSupplies, failures);
    }

    private Result writeMatchedSupplies(
            List<SupplyRow> rows,
            SupplyMatches matches,
            String panId
    ) {
        Map<Long, List<SupplyTarget>> targetsByRow = targetsByRow(rows);
        Set<String> retainedTargetIdentifiers = new HashSet<>();
        int updatedRows = 0;
        int createdTargets = 0;
        int updatedTargets = 0;
        for (MatchedSupply matched : matches.supplies()) {
            LhSupplyData source = matched.source();
            SupplyRow row = matched.row();
            String previousSourceIdentifier = row.getLhSourceSupplyRowIdentifier();
            if (row.enrichFromLh(
                    source.sourceIdentifier(), source.expectedMoveInMonth(), source.supplyHouseholdCount()
            )) {
                updatedRows++;
            }
            List<SupplyTarget> storedTargets = targetsByRow.get(row.getId());
            TargetWriteResult target = writeOrPreserveAmount(row, source, previousSourceIdentifier, storedTargets);
            retainedTargetIdentifiers.add(target.retainedIdentifier());
            createdTargets += target.created();
            updatedTargets += target.updated();
        }
        if (matches.failures().isEmpty()) {
            deleteStaleTargets(targetsByRow, retainedTargetIdentifiers, Set.of(panId));
        }
        else {
            targetsByRow.values().stream().flatMap(List::stream)
                    .filter(target -> !retainedTargetIdentifiers.contains(target.getSourceSupplyTargetIdentifier()))
                    .forEach(target -> markPreserved(target, "LH_SUPPLY_MATCHING_FAILED"));
        }
        return new Result(updatedRows, createdTargets, updatedTargets, matches.failures());
    }

    private Map<Long, List<SupplyTarget>> targetsByRow(List<SupplyRow> rows) {
        Map<Long, List<SupplyTarget>> targets = new HashMap<>();
        for (SupplyRow row : rows) {
            targets.put(row.getId(), new ArrayList<>());
        }
        if (rows.isEmpty()) {
            return targets;
        }
        List<Long> rowIds = rows.stream().map(SupplyRow::getId).toList();
        for (SupplyTarget target : supplyTargetRepository.findAllBySupplyRowIdIn(rowIds)) {
            targets.get(target.getSupplyRow().getId()).add(target);
        }
        return targets;
    }

    private LhAnnouncementEnrichmentRejectedException missingAmountForChangedHousingType() {
        return new LhAnnouncementEnrichmentRejectedException(
                LhAnnouncementEnrichmentFailureReason.INVALID_VALUE,
                "새 주택형의 임대금액이 없어 이전 주택형의 금액을 유지합니다."
        );
    }

    /** 보강의 금액만 보존 사유를 남긴다. 수기 공급대상은 LH 원천과 무관하다. */
    private static void markPreserved(SupplyTarget target, String reason) {
        if (LhSourceIdentifier.isLhSource(target.getSourceSupplyTargetIdentifier())) {
            target.markLhAmountPreserved(reason);
        }
    }

    private void deleteOtherLhTargets(List<SupplyTarget> storedTargets, String retainedIdentifier) {
        List<SupplyTarget> previousTargets = storedTargets.stream()
                .filter(target -> LhOwnedValues.owned(target.getSourceSupplyTargetIdentifier(),
                        target.isAdminModified()))
                .filter(target -> !retainedIdentifier.equals(target.getSourceSupplyTargetIdentifier()))
                .toList();
        supplyTargetRepository.deleteAll(previousTargets);
        storedTargets.removeAll(previousTargets);
    }

    private TargetWriteResult writeOrPreserveAmount(
            SupplyRow row,
            LhSupplyData source,
            String previousSourceIdentifier,
            List<SupplyTarget> storedTargets
    ) {
        var reviewed = storedTargets.stream()
                .filter(target -> LhOwnedValues.reviewed(target.getSourceSupplyTargetIdentifier(),
                        target.isAdminModified()))
                .findFirst();
        if (reviewed.isPresent()) {
            return new TargetWriteResult(reviewed.get().getSourceSupplyTargetIdentifier(), 0, 0);
        }
        String identifier = LhSourceIdentifier.target(source.sourceIdentifier());
        if (!hasCompleteAmount(source)) {
            String retainedIdentifier = existingAmountTargetIdentifier(
                    storedTargets, identifier, previousSourceIdentifier
            );
            storedTargets.stream()
                    .filter(target -> retainedIdentifier.equals(target.getSourceSupplyTargetIdentifier()))
                    .findFirst()
                    .ifPresent(target -> markPreserved(target, "LH_AMOUNT_NOT_PROVIDED"));
            return new TargetWriteResult(retainedIdentifier, 0, 0);
        }
        SupplyTarget stored = storedTargets.stream()
                .filter(target -> identifier.equals(target.getSourceSupplyTargetIdentifier()))
                .findFirst()
                .orElse(null);
        int createdCount = 0;
        int updatedCount = 0;
        if (stored == null) {
            SupplyTarget created = supplyTargetRepository.save(SupplyTarget.createFromSource(
                    row, identifier, ALL_TARGET, ALL_RANK, source.supplyHouseholdCount(),
                    source.rentalDeposit(), source.monthlyRent(), 1
            ));
            storedTargets.add(created);
            createdCount = 1;
        }
        else if (stored.updateFromSource(
                ALL_TARGET, ALL_RANK, source.supplyHouseholdCount(), source.rentalDeposit(), source.monthlyRent(), 1
        )) {
            updatedCount = 1;
        }
        deleteOtherLhTargets(storedTargets, identifier);
        return new TargetWriteResult(identifier, createdCount, updatedCount);
    }

    private boolean hasCompleteAmount(LhSupplyData source) {
        return source.rentalDeposit() != null && source.monthlyRent() != null;
    }

    /** 금액이 빠진 LH 공급이 이어 쓸 기존 공급대상. 지금 식별자, 이전 LH 공급 식별자, 다른 LH 공급대상 순으로 찾는다. */
    private String existingAmountTargetIdentifier(
            List<SupplyTarget> storedTargets,
            String currentIdentifier,
            String previousSourceIdentifier
    ) {
        if (storedTargets.stream().anyMatch(target ->
                currentIdentifier.equals(target.getSourceSupplyTargetIdentifier()))) {
            return currentIdentifier;
        }
        if (previousSourceIdentifier != null) {
            String previousIdentifier = LhSourceIdentifier.target(previousSourceIdentifier);
            if (storedTargets.stream().anyMatch(target ->
                    previousIdentifier.equals(target.getSourceSupplyTargetIdentifier()))) {
                return previousIdentifier;
            }
        }
        return storedTargets.stream()
                .map(SupplyTarget::getSourceSupplyTargetIdentifier)
                .filter(LhSourceIdentifier::isLhSource)
                .findFirst()
                .orElse(currentIdentifier);
    }

    private void deleteStaleTargets(
            Map<Long, List<SupplyTarget>> targetsByRow,
            Set<String> retained,
            Set<String> replacedPanIds
    ) {
        for (List<SupplyTarget> storedTargets : targetsByRow.values()) {
            List<SupplyTarget> stale = storedTargets.stream()
                    .filter(target -> LhOwnedValues.ownedFrom(target.getSourceSupplyTargetIdentifier(),
                            target.isAdminModified(), replacedPanIds))
                    .filter(target -> !retained.contains(target.getSourceSupplyTargetIdentifier()))
                    .toList();
            supplyTargetRepository.deleteAll(stale);
        }
    }

    private record MatchedSupply(LhSupplyData source, SupplyRow row) {
    }

    private record SupplyMatches(List<MatchedSupply> supplies, List<LhSupplyMatchingFailureData> failures) {
    }

    private record TargetWriteResult(
            String retainedIdentifier,
            int created,
            int updated
    ) {
    }

    record Result(
            int updatedRows,
            int createdTargets,
            int updatedTargets,
            List<LhSupplyMatchingFailureData> failures
    ) {
    }
}
