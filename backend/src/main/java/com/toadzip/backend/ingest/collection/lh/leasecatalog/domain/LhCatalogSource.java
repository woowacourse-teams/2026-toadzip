package com.toadzip.backend.ingest.collection.lh.leasecatalog.domain;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.projection.LhCatalogSourceSnapshot;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LhCatalogSource {

    private Long id;

    private Integer sourceOrder;
    private Instant collectedAt;
    private String areaName;
    private String supplyTypeName;
    private String complexLabel;
    private String complexTotalUnitCount;
    private String exclusiveArea;
    private String totalUnitCount;
    private String depositText;
    private String monthlyRentText;

    public LhCatalogSource(int sourceOrder, LhCatalogSourceSnapshot snapshot) {
        this.sourceOrder = sourceOrder;
        areaName = trim(snapshot.areaName());
        supplyTypeName = trim(snapshot.supplyTypeName());
        complexLabel = trim(snapshot.complexLabel());
        complexTotalUnitCount = trim(snapshot.complexTotalUnitCount());
        exclusiveArea = trim(snapshot.exclusiveArea());
        totalUnitCount = trim(snapshot.totalUnitCount());
        depositText = trim(snapshot.depositText());
        monthlyRentText = trim(snapshot.monthlyRentText());
    }

    public static LhCatalogSource read(Long id, int order, Instant collectedAt,
            com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot snapshot) {
        var normalized = new LhCatalogSourceSnapshot(snapshot.areaName(), snapshot.supplyTypeName(),
                snapshot.complexLabel(), snapshot.complexTotalUnitCount(), snapshot.exclusiveArea(),
                snapshot.totalUnitCount(), snapshot.depositText(), snapshot.monthlyRentText());
        var source = new LhCatalogSource(order, normalized);
        source.id = id;
        source.collectedAt = collectedAt;
        return source;
    }

    public void markCollectedAt(Instant collectedAt) {
        if (collectedAt == null) {
            throw new IllegalArgumentException("수집 시각은 필수입니다.");
        }
        this.collectedAt = collectedAt;
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
