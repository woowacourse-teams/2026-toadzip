package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.repository.LhAnnouncementCollectionCheckpointRepository;
import com.toadzip.backend.ingest.repository.LhAnnouncementSupplySourceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MyHomeAnnouncementSupplyRowResolver {

    private static final int AREA_SCALE = 4;

    private final LhAnnouncementCollectionCheckpointRepository checkpointRepository;

    private final LhAnnouncementSupplySourceRepository lhSupplyRepository;

    public MyHomeAnnouncementSupplyRowResolver(
            LhAnnouncementCollectionCheckpointRepository checkpointRepository,
            LhAnnouncementSupplySourceRepository lhSupplyRepository
    ) {
        this.checkpointRepository = checkpointRepository;
        this.lhSupplyRepository = lhSupplyRepository;
    }

    public MyHomeAnnouncementMappingData resolve(MyHomeAnnouncementMappingData data) {
        List<LhAnnouncementSupplySource> lhSupplies = findLhSupplies(data.sourceAnnouncementIdentifier());
        if (lhSupplies.isEmpty()) {
            return data;
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

    private List<LhAnnouncementSupplySource> findLhSupplies(String announcementIdentifier) {
        List<LhAnnouncementCollectionCheckpoint> checkpoints = checkpointRepository
                .findAllBySourceAndSourceAnnouncementKeyOrderByIdAsc(
                        ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                        announcementIdentifier
                );
        Set<String> panIds = new LinkedHashSet<>();
        for (LhAnnouncementCollectionCheckpoint checkpoint : checkpoints) {
            panIds.add(checkpoint.getPanId());
        }
        if (panIds.isEmpty()) {
            return List.of();
        }
        return lhSupplyRepository.findAllByPanIdInOrderByPanIdAscSourceOrderAsc(panIds);
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
                .filter(row -> MyHomeSupplyNameNormalizer.sameComplex(row.sourceComplexName(), lhComplexLabel))
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
        return new MyHomeSupplyRowMappingData(
                sourceRow.source(),
                sourceIdentifier(announcementIdentifier, sourceRow, lhSupply, preserveOriginalIdentifier),
                sourceRow.sourceComplexName(),
                sourceHousingTypeName,
                sourceRow.pnu(),
                sourceRow.complexSupplyType(),
                sourceRow.supplyCategory(),
                nonNegativeInteger(lhSupply.getSuppliedUnitCount()),
                area(lhSupply.getExclusiveArea()),
                area(lhSupply.getSupplyArea())
        );
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
