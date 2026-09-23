package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementSupplySourceRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementLinkResolutionException;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementLinkResolver;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MyHomeAnnouncementSupplyRowResolver {

    private static final int AREA_SCALE = 4;

    private final LhAnnouncementLinkResolver linkResolver;

    private final LhAnnouncementSupplySourceRepository lhSupplyRepository;

    public MyHomeAnnouncementSupplyRowResolver(
            LhAnnouncementLinkResolver linkResolver,
            LhAnnouncementSupplySourceRepository lhSupplyRepository
    ) {
        this.linkResolver = linkResolver;
        this.lhSupplyRepository = lhSupplyRepository;
    }

    public MyHomeAnnouncementMappingData resolve(MyHomeAnnouncementMappingData data) {
        if (data.provider() != AgencyCode.LH) {
            return data;
        }
        List<LhAnnouncementSupplySource> lhSupplies = findLhSupplies(data.supplyRows().getFirst().source());
        if (lhSupplies.isEmpty()) {
            return data.preservingExistingLhResolvedRows();
        }
        Map<MyHomeSupplyRowMappingData, List<LhAnnouncementSupplySource>> matched = matchByComplex(
                data.supplyRows(),
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
        return data.withSupplyRows(List.copyOf(resolved));
    }

    private List<LhAnnouncementSupplySource> findLhSupplies(MyHomeAnnouncementSource source) {
        String panId;
        try {
            panId = linkResolver.resolve(source);
        }
        catch (LhAnnouncementLinkResolutionException exception) {
            MyHomeAnnouncementMappingFailureReason reason = switch (exception.reason()) {
                case REQUEST_UNSUPPORTED -> MyHomeAnnouncementMappingFailureReason.LH_COLLECTION_REQUEST_UNSUPPORTED;
                case LINK_NOT_FOUND -> MyHomeAnnouncementMappingFailureReason.LH_COLLECTION_LINK_NOT_FOUND;
                case LINK_MISMATCH -> MyHomeAnnouncementMappingFailureReason.LH_COLLECTION_LINK_MISMATCH;
            };
            throw new MyHomeAnnouncementMappingRejectedException(reason, exception.getMessage());
        }
        return lhSupplyRepository.findAllByPanIdOrderBySourceOrderAsc(panId);
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
        Integer totalSupplyHouseholdCount = lhSupplyHouseholdCount == null
                ? sourceRow.totalSupplyHouseholdCount()
                : lhSupplyHouseholdCount;
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
        }
        catch (NumberFormatException exception) {
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
        }
        catch (NumberFormatException exception) {
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
}
