package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementLinkResolver;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhAnnouncementSupplySourceReader;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementLinkResolutionException;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public ResolvedAnnouncement resolve(MyHomeAnnouncementMappingData data) {
        List<MyHomeSupplyRowMappingData> currentRows = currentRows(data.supplyRows());
        Set<String> currentKeys = currentRows.stream().map(row -> row.source().getSourceKey())
                .collect(Collectors.toSet());
        Set<String> historicalKeys = data.supplyRows().stream().map(row -> row.source().getSourceKey())
                .filter(key -> !currentKeys.contains(key)).collect(Collectors.toSet());
        if (data.provider() != AgencyCode.LH) {
            return new ResolvedAnnouncement(data, null, List.of(), historicalKeys);
        }
        LhAnnouncementRequest request = resolveRequest(currentRows);
        List<LhAnnouncementSupplySource> lhSupplies = lhSupplyRepository
                .findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                        request.panId(),
                        LhAnnouncementQuery.requestHashOf(request.requestDescription())
                );
        if (lhSupplies.isEmpty()) {
            return new ResolvedAnnouncement(data, request, lhSupplies, historicalKeys);
        }
        Map<MyHomeSupplyRowMappingData, List<LhAnnouncementSupplySource>> matched = matchByComplex(
                currentRows,
                lhSupplies
        );
        List<MyHomeSupplyRowMappingData> resolved = new ArrayList<>();
        for (MyHomeSupplyRowMappingData sourceRow : data.supplyRows()) {
            List<LhAnnouncementSupplySource> matchedSupplies = matched.get(sourceRow);
            if (matchedSupplies == null || matchedSupplies.isEmpty()) {
                resolved.add(sourceRow);
                continue;
            }
            for (int index = 0; index < matchedSupplies.size(); index++) {
                LhAnnouncementSupplySource lhSupply = matchedSupplies.get(index);
                resolved.add(resolveRow(
                        data.sourceAnnouncementIdentifier(),
                        sourceRow,
                        lhSupply,
                        index == 0
                ));
            }
        }
        return new ResolvedAnnouncement(
                data.withSupplyRows(List.copyOf(resolved)), request, lhSupplies, historicalKeys
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
        List<MyHomeAnnouncementSource> currentSources = MyHomeAnnouncementCurrentSources.select(
                sourceRows.stream().map(MyHomeSupplyRowMappingData::source).toList()
        );
        return sourceRows.stream().filter(row -> currentSources.contains(row.source())).toList();
    }

    private Map<MyHomeSupplyRowMappingData, List<LhAnnouncementSupplySource>> matchByComplex(
            List<MyHomeSupplyRowMappingData> sourceRows,
            List<LhAnnouncementSupplySource> lhSupplies
    ) {
        Map<MyHomeSupplyRowMappingData, List<LhAnnouncementSupplySource>> matched = new LinkedHashMap<>();
        for (LhAnnouncementSupplySource lhSupply : lhSupplies) {
            MyHomeSupplyRowMappingData sourceRow = uniqueSourceRow(sourceRows, lhSupply.getComplexLabel());
            if (sourceRow == null) {
                continue;
            }
            matched.computeIfAbsent(sourceRow, ignored -> new ArrayList<>()).add(lhSupply);
        }
        return matched;
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
            LhAnnouncementSupplySource lhSupply,
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
                lhSourceIdentifier(lhSupply)
        );
    }

    private String lhSourceIdentifier(LhAnnouncementSupplySource source) {
        return "LH:" + source.getPanId() + ":SUPPLY:" + source.getSourceOrder();
    }

    private String sourceIdentifier(
            String announcementIdentifier,
            MyHomeSupplyRowMappingData sourceRow,
            LhAnnouncementSupplySource source,
            boolean preserveOriginalIdentifier
    ) {
        if (preserveOriginalIdentifier) {
            return sourceRow.sourceSupplyRowIdentifier();
        }
        return announcementIdentifier + ":LH:" + source.getPanId() + ":" + source.getSourceOrder();
    }

    private String sourceHousingTypeName(
            MyHomeSupplyRowMappingData sourceRow,
            LhAnnouncementSupplySource lhSupply
    ) {
        String lhTypeName = normalizedText(lhSupply.getTypeName());
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
        String normalized = normalizedText(raw);
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

    private String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    record ResolvedAnnouncement(
            MyHomeAnnouncementMappingData data,
            LhAnnouncementRequest request,
            List<LhAnnouncementSupplySource> supplies,
            Set<String> historicalSourceKeys
    ) {

        boolean preserveExistingLhResolvedRows() {
            return request != null && supplies.isEmpty();
        }

        Set<String> sourceKeysExcludedFromLhEnrichment() {
            return data.supplyRows().stream().map(MyHomeSupplyRowMappingData::source)
                    .filter(source -> historicalSourceKeys.contains(source.getSourceKey())
                            || !LhProviderPolicy.isLh(source.getSuplyInsttNm()))
                    .map(MyHomeAnnouncementSource::getSourceKey)
                    .collect(Collectors.toSet());
        }
    }
}
