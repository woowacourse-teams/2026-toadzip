package com.toadzip.backend.ingest.lh.refinement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.housing.domain.HousingTypeNames;
import com.toadzip.backend.announcement.domain.LhSupplyMatchStatus;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.HousingTypeAreas;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.lh.refinement.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.domain.SourceArea;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import com.toadzip.backend.ingest.lh.refinement.dto.LhAnnouncementRefinementReport.SupplyCounts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LH 공급을 (공고 단지, 주택형(신청형)) 공급행으로 쓴다({@link LhSourceIdentifier#lhOwnedSupplyRow}). LH는 같은 신청형을 신청자 묶음마다
 * 여러 줄로 주므로("36형 일반(고령자)"·"36형 일반(청년)") 줄마다 공급행을 만들지 않고 대상으로 둔다. 같은 전용면적이어도 신청형이 다르면
 * (55A1·55C(주거약자)) 공급행을 따로 두고 같은 단지 주택형을 함께 가리킨다({@link HousingTypeNames}). 같은 형명의 주거약자용 줄
 * ("26A"와 "26A(주거약자)")도 공고문에서는 다른 주택형이라 따로 둔다. 이때만 키에 묶음을 붙여 기존 공급행 식별자를 바꾸지 않는다.
 * 대표 주택형은 연결 단지에서 {@link HousingTypeAreas#pick}으로 고르고,
 * 정제는 주택형을 만들지 않는다(LH 면적은 함께 모집하는 면적 중 하나라 PDF 보정이 확인할 때 만든다). 공고문 소유 공고는 공급행을
 * 만들거나 지우지 않고 비어 있는 공고 단지 참조만 채운다.
 */
@Component
@RequiredArgsConstructor
class LhSupplyRowWriter {

    private static final String ALL = "전체";
    private static final int MAX_TYPE_NAME = 255;

    private final SupplyRowRepository supplyRows;
    private final SupplyTargetRepository supplyTargets;
    private final HousingTypeRepository housingTypes;

    /**
     * LH 원천 소유 공고의 공급행을 이번 LH 공급으로 맞추고 이 공고의 다른 공급행을 지운다. 공급행은 (공고 단지, 신청형)마다 하나이고,
     * 같은 신청형의 LH 줄(신청자 묶음)은 그 공급행의 대상이 된다. 공급행의 전용면적은 그 신청형의 첫 LH 줄 면적이다.
     */
    void writeLhOwned(Announcement announcement, List<SupplyLine> lines, Map<String, AnnouncementComplex> complexes,
            SupplyCategory category, Tally tally) {
        Map<String, SupplyRow> stored = supplyRows.findAllByAnnouncement(announcement).stream()
                .collect(Collectors.toMap(SupplyRow::getSourceSupplyRowIdentifier, row -> row));
        Map<Long, List<HousingType>> typesByComplex = new HashMap<>();
        int order = 0;
        for (Map.Entry<String, List<SupplyLine>> group : groups(lines, complexes).entrySet()) {
            String key = group.getKey();
            List<SupplyLine> members = group.getValue();
            SupplyLine first = members.getFirst();
            AnnouncementComplex complex = complexes.get(first.source().getComplexLabel());
            BigDecimal area = members.stream().map(line -> area(line.source().getExclusiveArea())).filter(Objects::nonNull)
                    .findFirst().orElse(null);
            String typeName = joinedTypeName(members);
            HousingType type = complex.linkedComplex() == null ? null : HousingTypeAreas.pick(typesByComplex
                    .computeIfAbsent(complex.linkedComplex().getId(), ignored -> housingTypes.findAllByHousingComplex(
                            complex.linkedComplex())), area, first.source().getTypeName()).orElse(null);
            LhSupplyMatchStatus status = status(complex, type, members);
            SupplyRow incoming = SupplyRow.createFromLh(announcement, complex, type, key, first.data().sourceIdentifier(),
                    order++, typeName, area, first.data().expectedMoveInMonth(), category, totalCount(members), status);
            SupplyRow row = upsert(stored.remove(key), incoming, tally);
            writeTargets(row, members, tally);
            tally.count(status);
        }
        for (SupplyRow stale : stored.values()) {
            supplyTargets.deleteAll(supplyTargets.findAllBySupplyRow(stale));
            supplyRows.delete(stale);
            tally.deletedRows++;
        }
    }

    /** LH 줄을 공급행 키별로 모은다. LH 순서대로 처음 나온 묶음이 앞선다. 형명이 빈 줄은 줄마다 공급행을 둔다. */
    private static Map<String, List<SupplyLine>> groups(List<SupplyLine> lines, Map<String, AnnouncementComplex> complexes) {
        // 같은 단지에 주거약자용 표기가 없는 같은 형명이 있으면 주거약자용 줄은 다른 주택형이다("26A"와 "26A(주거약자)").
        Set<String> plainKeys = lines.stream().filter(line -> !HousingTypeNames.housingVariant(line.source().getTypeName()))
                .map(line -> complexes.get(line.source().getComplexLabel()).getId() + ":"
                        + HousingTypeNames.key(line.source().getTypeName()))
                .collect(Collectors.toSet());
        Map<String, List<SupplyLine>> groups = new LinkedHashMap<>();
        Map<Long, Integer> unnamed = new HashMap<>();
        for (SupplyLine line : lines) {
            AnnouncementComplex complex = complexes.get(line.source().getComplexLabel());
            String typeName = line.source().getTypeName();
            String typeKey = HousingTypeNames.key(typeName);
            if (HousingTypeNames.housingVariant(typeName) && plainKeys.contains(complex.getId() + ":" + typeKey)) {
                typeKey = HousingTypeNames.applicationKey(typeName);
            }
            int occurrence = typeKey.isEmpty() ? unnamed.merge(complex.getId(), 1, Integer::sum) : 1;
            String key = LhSourceIdentifier.lhOwnedSupplyRow(complex.getId(), typeKey, occurrence);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(line);
        }
        return groups;
    }

    /** 원천 형명은 LH 형명 원문을 순서대로 잇는다. 신청자 표기만 다른 형명이 여럿이면 모두 보인다. */
    private static String joinedTypeName(List<SupplyLine> members) {
        String joined = members.stream().map(line -> line.source().getTypeName().strip()).distinct()
                .collect(Collectors.joining(" · "));
        return joined.length() <= MAX_TYPE_NAME ? joined : joined.substring(0, MAX_TYPE_NAME - 1) + "…";
    }

    /** 공급행 모집수는 LH 줄 모집수의 합이다. 한 줄이라도 모르면 합을 정하지 않는다. */
    private static Integer totalCount(List<SupplyLine> members) {
        int sum = 0;
        for (SupplyLine line : members) {
            Integer count = line.data().supplyHouseholdCount();
            if (count == null) {
                return null;
            }
            sum += count;
        }
        return sum;
    }

    private SupplyRow upsert(SupplyRow stored, SupplyRow incoming, Tally tally) {
        if (stored == null) {
            SupplyRow saved = supplyRows.save(incoming);
            tally.createdRows++;
            return saved;
        }
        if (stored.updateFromLh(incoming)) {
            tally.updatedRows++;
        }
        return stored;
    }

    /**
     * 공고문 소유 공급행은 값·단지·주택형 참조를 그대로 두고 공고 단지 참조만 채운다. 공고 단지가 하나뿐이거나, 그 행의 단지에 연결된 공고 단지가
     * 하나일 때만 정한다. 그 밖에는 비워 둔다.
     */
    void fillDocumentOwned(Announcement announcement, Map<String, AnnouncementComplex> complexes, Tally tally) {
        List<AnnouncementComplex> all = new ArrayList<>(complexes.values());
        for (SupplyRow row : supplyRows.findAllByAnnouncement(announcement)) {
            if (row.getAnnouncementComplex() != null) {
                continue;
            }
            Optional<AnnouncementComplex> complex = soleComplex(all, row.getHousingComplex());
            if (complex.isPresent() && row.fillAnnouncementComplex(complex.get())) {
                tally.filledComplexes++;
            }
        }
    }

    private Optional<AnnouncementComplex> soleComplex(List<AnnouncementComplex> complexes, HousingComplex rowComplex) {
        if (complexes.size() == 1) {
            return Optional.of(complexes.getFirst());
        }
        if (rowComplex == null) {
            return Optional.empty();
        }
        List<AnnouncementComplex> linked = complexes.stream()
                .filter(complex -> complex.linkedComplex() != null
                        && complex.linkedComplex().getId().equals(rowComplex.getId()))
                .toList();
        if (linked.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(linked.getFirst());
    }

    /**
     * LH 줄마다 대상 하나를 둔다. 대상 키는 LH 줄 식별자에서 만든다. 한 줄뿐인 공급행은 지금처럼 금액이 있을 때만 대상("전체")을 둔다.
     * 여러 줄이면 모집수를 대상별로 보이도록 금액이 없어도 둔다.
     */
    private void writeTargets(SupplyRow row, List<SupplyLine> members, Tally tally) {
        Map<String, SupplyTarget> stored = supplyTargets.findAllBySupplyRow(row).stream()
                .filter(target -> target.getSourceSupplyTargetIdentifier() != null)
                .collect(Collectors.toMap(SupplyTarget::getSourceSupplyTargetIdentifier, target -> target,
                        (first, second) -> first));
        Map<String, Long> labelCounts = members.stream().collect(Collectors.groupingBy(
                line -> targetLabel(line, members.size()), Collectors.counting()));
        int order = 1;
        for (SupplyLine line : members) {
            LhSupplyData data = line.data();
            String identifier = LhSourceIdentifier.target(data.sourceIdentifier());
            boolean priced = data.rentalDeposit() != null && data.monthlyRent() != null;
            if (!priced && members.size() == 1) {
                continue;
            }
            // 같은 신청자 표기가 여러 줄(26A(고령자)·26B(고령자))이면 대상을 가를 수 있게 LH 형명 원문을 쓴다.
            String label = labelCounts.get(targetLabel(line, members.size())) > 1 ? line.source().getTypeName().strip()
                    : targetLabel(line, members.size());
            SupplyTarget target = stored.remove(identifier);
            if (target == null) {
                supplyTargets.save(SupplyTarget.createFromSource(row, identifier, label, ALL, data.supplyHouseholdCount(),
                        data.rentalDeposit(), data.monthlyRent(), order));
                tally.createdTargets++;
            } else if (target.updateFromSource(label, ALL, data.supplyHouseholdCount(), data.rentalDeposit(),
                    data.monthlyRent(), order)) {
                tally.updatedTargets++;
            }
            order++;
        }
        for (SupplyTarget stale : stored.values()) {
            supplyTargets.delete(stale);
            tally.deletedTargets++;
        }
    }

    /**
     * 대상 이름. LH 형명 끝의 신청자 표기("36형 일반(고령자)" → "고령자", "59A[청년]" → "청년")를 쓴다. 신청자 표기가 없으면 한 줄뿐인 공급행은 "전체",
     * 여러 줄이면 줄을 가를 수 있게 LH 형명 원문이다.
     */
    private static String targetLabel(SupplyLine line, int memberCount) {
        String typeName = line.source().getTypeName().strip();
        String applicant = HousingTypeNames.applicant(typeName);
        if (!applicant.isEmpty()) {
            return applicant;
        }
        return memberCount == 1 ? ALL : typeName;
    }

    private LhSupplyMatchStatus status(AnnouncementComplex complex, HousingType type, List<SupplyLine> members) {
        if (complex.linkedComplex() == null) {
            return LhSupplyMatchStatus.COMPLEX_UNLINKED;
        }
        if (type == null) {
            return LhSupplyMatchStatus.AREA_UNMATCHED;
        }
        if (members.stream().anyMatch(line -> line.data().rentalDeposit() == null || line.data().monthlyRent() == null)) {
            return LhSupplyMatchStatus.VALUES_MISSING;
        }
        return LhSupplyMatchStatus.MATCHED;
    }

    private static BigDecimal area(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SourceArea.normalized(new BigDecimal(value.strip()));
        }
        catch (NumberFormatException unreadable) {
            return null;
        }
    }

    record SupplyLine(LhAnnouncementSupplySourceView source, LhSupplyData data) {
    }

    static final class Tally {

        void add(Tally other) {
            createdRows += other.createdRows;
            updatedRows += other.updatedRows;
            deletedRows += other.deletedRows;
            createdTargets += other.createdTargets;
            updatedTargets += other.updatedTargets;
            deletedTargets += other.deletedTargets;
            filledComplexes += other.filledComplexes;
            other.statuses.forEach((status, count) -> statuses.merge(status, count, Integer::sum));
        }

        SupplyCounts toCounts() {
            Map<String, Integer> statusCounts = new TreeMap<>();
            statuses.forEach((status, count) -> statusCounts.put(status.name(), count));
            return new SupplyCounts(createdRows, updatedRows, deletedRows, createdTargets, updatedTargets,
                    deletedTargets, filledComplexes, statusCounts);
        }

        private int createdRows;
        private int updatedRows;
        private int deletedRows;
        private int createdTargets;
        private int updatedTargets;
        private int deletedTargets;
        private int filledComplexes;
        private final Map<LhSupplyMatchStatus, Integer> statuses = new EnumMap<>(LhSupplyMatchStatus.class);

        void count(LhSupplyMatchStatus status) {
            statuses.merge(status, 1, Integer::sum);
        }
    }
}
