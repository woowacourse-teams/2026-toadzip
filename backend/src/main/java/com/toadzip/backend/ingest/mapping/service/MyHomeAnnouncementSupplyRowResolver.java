package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.lh.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementLinkResolver;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.lh.collection.supply.repository.LhAnnouncementSupplySourceReader;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.domain.SourceText;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.enrichment.domain.LhEnrichmentTargetSources;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyRowLinks;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementLinkResolutionException;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MyHomeAnnouncementSupplyRowResolver {

    private static final int AREA_SCALE = 4;

    private final LhAnnouncementLinkResolver linkResolver;

    private final LhAnnouncementSupplySourceReader lhSupplyRepository;

    private final LhSupplyRowLinks links;

    public ResolvedAnnouncement resolve(MyHomeAnnouncementMappingData data) {
        List<MyHomeSupplyRowMappingData> currentRows = currentRows(data.supplyRows());
        if (data.provider() != AgencyCode.LH) {
            return new ResolvedAnnouncement(data, null, List.of(), Map.of());
        }
        LhAnnouncementRequest request = resolveRequest(currentRows);
        List<LhAnnouncementSupplySourceView> lhSupplies = lhSupplyRepository
                .findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                        request.panId(),
                        request.requestHash()
                );
        if (lhSupplies.isEmpty()) {
            return new ResolvedAnnouncement(data, request, lhSupplies, Map.of());
        }
        Map<String, LhSupplyRowLinks.State> supplyLinks = links.states(data.sourceAnnouncementIdentifier(),
                currentRows.stream().map(MyHomeSupplyRowMappingData::source).toList(), lhSupplies);
        Map<MyHomeSupplyRowMappingData, List<LhAnnouncementSupplySourceView>> matched = matchByComplex(
                currentRows,
                lhSupplies,
                supplyLinks
        );
        List<MyHomeSupplyRowMappingData> resolved = new ArrayList<>();
        for (MyHomeSupplyRowMappingData sourceRow : data.supplyRows()) {
            List<LhAnnouncementSupplySourceView> matchedSupplies = matched.get(sourceRow);
            if (matchedSupplies == null || matchedSupplies.isEmpty()) {
                resolved.add(sourceRow);
                continue;
            }
            for (int index = 0; index < matchedSupplies.size(); index++) {
                LhAnnouncementSupplySourceView lhSupply = matchedSupplies.get(index);
                resolved.add(resolveRow(
                        data.sourceAnnouncementIdentifier(),
                        sourceRow,
                        lhSupply,
                        index == 0
                ));
            }
        }
        return new ResolvedAnnouncement(
                data.withSupplyRows(List.copyOf(resolved)), request, lhSupplies, supplyLinks
        );
    }

    private LhAnnouncementRequest resolveRequest(List<MyHomeSupplyRowMappingData> sourceRows) {
        try {
            return linkResolver.resolveFirstLinked(
                    sourceRows.stream().map(MyHomeSupplyRowMappingData::source).toList()
            ).request();
        } catch (LhAnnouncementLinkResolutionException exception) {
            MyHomeAnnouncementMappingFailureReason reason = switch (exception.reason()) {
                case REQUEST_UNSUPPORTED -> MyHomeAnnouncementMappingFailureReason.LH_COLLECTION_REQUEST_UNSUPPORTED;
                case LINK_NOT_FOUND -> MyHomeAnnouncementMappingFailureReason.LH_COLLECTION_LINK_NOT_FOUND;
                case LINK_MISMATCH -> MyHomeAnnouncementMappingFailureReason.LH_COLLECTION_LINK_MISMATCH;
            };
            throw new MyHomeAnnouncementMappingRejectedException(reason, exception.getMessage());
        }
    }

    private List<MyHomeSupplyRowMappingData> currentRows(List<MyHomeSupplyRowMappingData> sourceRows) {
        List<MyHomeAnnouncementSourceView> currentSources = MyHomeAnnouncementCurrentSources.select(
                sourceRows.stream().map(MyHomeSupplyRowMappingData::source).toList()
        );
        return sourceRows.stream().filter(row -> currentSources.contains(row.source())).toList();
    }

    private Map<MyHomeSupplyRowMappingData, List<LhAnnouncementSupplySourceView>> matchByComplex(
            List<MyHomeSupplyRowMappingData> sourceRows,
            List<LhAnnouncementSupplySourceView> lhSupplies,
            Map<String, LhSupplyRowLinks.State> savedLinks
    ) {
        // 관리자가 저장한 연결을 먼저 쓴다. 저장 연결에 걸린 LH 행과 원천행은 단지명 자동 연결에서 뺀다.
        // 원천이 바뀐 저장 연결도 빼서 관리자 판단을 자동 연결이 조용히 대체하지 않게 한다.
        Map<MyHomeSupplyRowMappingData, List<LhAnnouncementSupplySourceView>> matched = new LinkedHashMap<>();
        Set<String> linkedRowKeys = savedLinks.values().stream()
                .map(LhSupplyRowLinks.State::myhomeSourceRowIdentifier).collect(Collectors.toSet());
        for (LhAnnouncementSupplySourceView lhSupply : lhSupplies) {
            linkedRow(sourceRows, savedLinks.get(LhSupplyRowLinks.identifier(lhSupply)))
                    .ifPresent(row -> matched.computeIfAbsent(row, ignored -> new ArrayList<>()).add(lhSupply));
        }
        List<MyHomeSupplyRowMappingData> automaticRows = sourceRows.stream()
                .filter(row -> !linkedRowKeys.contains(row.source().getSourceKey())).toList();
        List<LhAnnouncementSupplySourceView> automaticSupplies = lhSupplies.stream()
                .filter(supply -> !savedLinks.containsKey(LhSupplyRowLinks.identifier(supply))).toList();
        matched.putAll(matchByName(automaticRows, automaticSupplies));
        return matched;
    }

    private Optional<MyHomeSupplyRowMappingData> linkedRow(
            List<MyHomeSupplyRowMappingData> sourceRows,
            LhSupplyRowLinks.State saved
    ) {
        if (saved == null || !saved.valid()) {
            return Optional.empty();
        }
        return sourceRows.stream()
                .filter(row -> row.source().getSourceKey().equals(saved.myhomeSourceRowIdentifier()))
                .findFirst();
    }

    private Map<MyHomeSupplyRowMappingData, List<LhAnnouncementSupplySourceView>> matchByName(
            List<MyHomeSupplyRowMappingData> sourceRows,
            List<LhAnnouncementSupplySourceView> lhSupplies
    ) {
        Map<MyHomeSupplyRowMappingData, List<LhAnnouncementSupplySourceView>> matched = new LinkedHashMap<>();
        for (LhAnnouncementSupplySourceView lhSupply : lhSupplies) {
            MyHomeSupplyRowMappingData sourceRow = uniqueSourceRow(sourceRows, lhSupply.getComplexLabel());
            if (sourceRow == null) {
                continue;
            }
            matched.computeIfAbsent(sourceRow, ignored -> new ArrayList<>()).add(lhSupply);
        }
        // 단지명으로 잇고 남은 원천이 한 행뿐이고 남은 LH 공급행의 모집수 합계가 같으면 같은 공급이다.
        // 표기만 다른 경우(아산읍내 ↔ 아산읍내1 영구임대아파트, 평화주공4단지 ↔ 전주평화4 영구임대주택)를 잇는다.
        List<MyHomeSupplyRowMappingData> remainingRows = sourceRows.stream()
                .filter(row -> !matched.containsKey(row)).toList();
        List<LhAnnouncementSupplySourceView> remainingSupplies = lhSupplies.stream()
                .filter(supply -> matched.values().stream().noneMatch(list -> list.contains(supply))).toList();
        if (remainingRows.size() == 1 && !remainingSupplies.isEmpty()
                && sameTotal(remainingRows.getFirst(), remainingSupplies)) {
            matched.put(remainingRows.getFirst(), new ArrayList<>(remainingSupplies));
        }
        return matched;
    }

    private boolean sameTotal(MyHomeSupplyRowMappingData sourceRow, List<LhAnnouncementSupplySourceView> lhSupplies) {
        if (sourceRow.totalSupplyHouseholdCount() == null) {
            return false;
        }
        long total = 0;
        for (LhAnnouncementSupplySourceView lhSupply : lhSupplies) {
            Integer count = nonNegativeInteger(lhSupply.getSuppliedUnitCount());
            if (count == null) {
                return false;
            }
            total += count;
        }
        return total == sourceRow.totalSupplyHouseholdCount();
    }

    private MyHomeSupplyRowMappingData uniqueSourceRow(
            List<MyHomeSupplyRowMappingData> sourceRows,
            String lhComplexLabel
    ) {
        List<MyHomeSupplyRowMappingData> matched = sourceRows.stream()
                .filter(row -> SupplyNameNormalizer.compatibleComplex(row.sourceComplexName(), lhComplexLabel))
                .toList();
        if (matched.size() != 1) {
            return null;
        }
        return matched.getFirst();
    }

    private MyHomeSupplyRowMappingData resolveRow(
            String announcementIdentifier,
            MyHomeSupplyRowMappingData sourceRow,
            LhAnnouncementSupplySourceView lhSupply,
            boolean preserveOriginalIdentifier
    ) {
        String sourceHousingTypeName = sourceHousingTypeName(sourceRow, lhSupply);
        Integer lhSupplyHouseholdCount = nonNegativeInteger(lhSupply.getSuppliedUnitCount());
        Integer totalSupplyHouseholdCount = lhSupplyHouseholdCount;
        if (totalSupplyHouseholdCount == null) {
            totalSupplyHouseholdCount = sourceRow.totalSupplyHouseholdCount();
        }
        return new MyHomeSupplyRowMappingData(
                sourceRow.source(),
                sourceIdentifier(announcementIdentifier, sourceRow, lhSupply, preserveOriginalIdentifier),
                sourceRow.sourceComplexName(),
                sourceHousingTypeName,
                sourceRow.pnu(),
                sourceRow.complexSupplyType(),
                sourceRow.supplyCategory(),
                totalSupplyHouseholdCount,
                area(lhSupply.getExclusiveArea()),
                area(lhSupply.getSupplyArea()),
                lhSupplyHouseholdCount,
                LhSupplyRowLinks.identifier(lhSupply)
        );
    }

    private String sourceIdentifier(
            String announcementIdentifier,
            MyHomeSupplyRowMappingData sourceRow,
            LhAnnouncementSupplySourceView source,
            boolean preserveOriginalIdentifier
    ) {
        if (preserveOriginalIdentifier) {
            return sourceRow.sourceSupplyRowIdentifier();
        }
        return LhSourceIdentifier.supplyRow(announcementIdentifier, source.getPanId(), source.getSourceOrder());
    }

    private String sourceHousingTypeName(
            MyHomeSupplyRowMappingData sourceRow,
            LhAnnouncementSupplySourceView lhSupply
    ) {
        String lhTypeName = SourceText.strippedOrNull(lhSupply.getTypeName());
        if (lhTypeName != null) {
            return lhTypeName;
        }
        return sourceRow.sourceHousingTypeName();
    }

    private BigDecimal area(String raw) {
        String normalized = normalizedNumber(raw);
        if (normalized == null) {
            return null;
        }
        try {
            return new BigDecimal(normalized).setScale(AREA_SCALE, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer nonNegativeInteger(String raw) {
        String normalized = normalizedNumber(raw);
        if (normalized == null || normalized.contains(".")) {
            return null;
        }
        try {
            int value = Integer.parseInt(normalized);
            if (value < 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalizedNumber(String raw) {
        String normalized = SourceText.strippedOrNull(raw);
        if (normalized == null) {
            return null;
        }
        String withoutGrouping = normalized.replace(",", "");
        String withoutUnit = withoutGrouping.replace("㎡", "").strip();
        if (!withoutUnit.matches("[0-9]+(?:\\.[0-9]+)?")) {
            return null;
        }
        return withoutUnit;
    }

    record ResolvedAnnouncement(
            MyHomeAnnouncementMappingData data,
            LhAnnouncementRequest request,
            List<LhAnnouncementSupplySourceView> supplies,
            Map<String, LhSupplyRowLinks.State> supplyLinks
    ) {

        Set<String> sourceKeysExcludedFromLhEnrichment() {
            return LhEnrichmentTargetSources.excludedSourceKeys(
                    data.supplyRows().stream().map(MyHomeSupplyRowMappingData::source).toList());
        }
    }
}
