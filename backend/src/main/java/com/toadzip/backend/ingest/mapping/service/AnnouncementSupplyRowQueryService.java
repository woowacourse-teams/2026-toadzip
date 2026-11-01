package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowGroup;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowGroupRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SplitGroup;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SplitRow;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공급 보정 화면이 보는 원천행별 매칭 상태. 분할 보정한 원천은 보정 공급행 묶음으로, 나머지는 자동·저장 매칭으로 보인다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnouncementSupplyRowQueryService {
    private final SupplyCorrectionSources sources;
    private final MyHomeAnnouncementSupplyMatcher matcher;
    private final AnnouncementRepository announcements;
    private final SavedSupplyMatches savedMatches;
    private final SupplyMatchingFingerprint fingerprints;
    private final SupplyRowRepository productRows;
    private final SupplyRowGroupRepository groups;
    private final AnnouncementSupplySplitWriter splitWriter;

    public List<Row> rows(String identifier) {
        var result = new LinkedHashMap<String, Row>();
        var excluded = announcements.findBySourceAnnouncementIdentifier(identifier)
                .map(value -> productRows.findAllByAnnouncement(value).stream()
                        .filter(SupplyRow::isAdminExcluded).map(SupplyRow::getSourceSupplyRowIdentifier)
                        .collect(Collectors.toSet()))
                .orElse(Set.<String>of());
        var supplyRows = sources.supplyRows(identifier);
        var expanded = SupplyRowGroupOrigins.expandedIn(supplyRows);
        for (var data : supplyRows) {
            var group = groups.findBySourceRowIdentifier(SupplyRowGroupOrigins.key(data)).orElse(null);
            if (group != null) {
                result.putIfAbsent(SupplyRowGroupOrigins.key(data), splitRow(data, group, expanded.apply(data)));
                continue;
            }
            result.put(data.sourceSupplyRowIdentifier(),
                    matchedRow(data, excluded.contains(data.sourceSupplyRowIdentifier())));
        }
        return List.copyOf(result.values());
    }

    private Row splitRow(MyHomeSupplyRowMappingData data, SupplyRowGroup group, boolean expanded) {
        var members = productRows.findAllByAnnouncement(group.getAnnouncement()).stream()
                .filter(row -> row.getSupplyGroup() != null
                        && row.getSupplyGroup().getId().equals(group.getId()))
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder))
                .toList();
        boolean review = splitWriter.reviewRequired(group, data, expanded, members);
        var split = new SplitGroup(group.getId(), group.getSharedHouseholdCount(), group.getSourceUrl(),
                group.getReason(), group.getActor(), group.getUpdatedAt(), review,
                members.stream().map(row -> new SplitRow(row.getId(), row.displayHousingTypeName(),
                        complexId(row), typeId(row))).toList());
        Long groupComplexId = null;
        if (!members.isEmpty()) {
            groupComplexId = complexId(members.getFirst());
        }
        String failure = null;
        if (review) {
            failure = "보존한 공급행의 원천 또는 주택형이 변경되었습니다. 근거를 다시 확인해 주세요.";
        }
        return new Row(data.sourceSupplyRowIdentifier(), sources.token(data),
                data.sourceComplexName(), SupplyRowGroupOrigins.housingTypeName(data, expanded),
                data.pnu(), null, null, groupComplexId, null, failure, null,
                SupplyRowGroupOrigins.householdCount(data, expanded), split, false);
    }

    private Row matchedRow(MyHomeSupplyRowMappingData data, boolean excluded) {
        var match = matcher.match(data);
        Long complexId = null;
        Long typeId = null;
        if (match.complex() != null) {
            complexId = match.complex().getId();
        }
        if (match.housingType() != null) {
            typeId = match.housingType().getId();
        }
        return new Row(data.sourceSupplyRowIdentifier(), sources.token(data),
                data.sourceComplexName(), data.sourceHousingTypeName(), data.pnu(),
                data.exclusiveArea(), data.supplyArea(), complexId, typeId, match.failureDetail(),
                savedMatches.view(data, match.failure() != null), data.source().getSumSuplyCo(), null, excluded);
    }

    private Long complexId(SupplyRow row) {
        if (row.getHousingComplex() == null) {
            return null;
        }
        return row.getHousingComplex().getId();
    }

    private Long typeId(SupplyRow row) {
        if (row.getHousingType() == null) {
            return null;
        }
        return row.getHousingType().getId();
    }

    /**
     * 공급 보정에서 새 공통 모집 묶음을 만들 원천. LH 공급정보로 여러 행이 된 마이홈 원천의 행이면 그 행 단위의 묶음 키와
     * 원천 지문을 준다. 그렇지 않거나 원천을 읽을 수 없으면 비워 두며, 호출자는 마이홈 원천 단위로 묶는다.
     */
    public Optional<SplitOrigin> expandedSplitOrigin(String identifier, String rowIdentifier) {
        List<MyHomeSupplyRowMappingData> rows;
        try {
            rows = sources.supplyRows(identifier);
        }
        catch (InvalidIngestRequestException exception) {
            return Optional.empty();
        }
        var expanded = SupplyRowGroupOrigins.expandedIn(rows);
        return rows.stream().filter(row -> row.sourceSupplyRowIdentifier().equals(rowIdentifier) && expanded.apply(row))
                .findFirst().map(row -> new SplitOrigin(SupplyRowGroupOrigins.key(row), row.source().getSourceKey(),
                        fingerprints.splitSource(row, true)));
    }

    public record SplitOrigin(String key, String myHomeSourceKey, String fingerprint) { }
}
