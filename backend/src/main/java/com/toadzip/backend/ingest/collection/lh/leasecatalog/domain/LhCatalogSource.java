package com.toadzip.backend.ingest.collection.lh.leasecatalog.domain;

import com.toadzip.backend.ingest.domain.SourceText;
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
        areaName = SourceText.strippedOrNull(snapshot.areaName());
        supplyTypeName = SourceText.strippedOrNull(snapshot.supplyTypeName());
        complexLabel = SourceText.strippedOrNull(snapshot.complexLabel());
        complexTotalUnitCount = SourceText.strippedOrNull(snapshot.complexTotalUnitCount());
        exclusiveArea = SourceText.strippedOrNull(snapshot.exclusiveArea());
        totalUnitCount = SourceText.strippedOrNull(snapshot.totalUnitCount());
        depositText = SourceText.strippedOrNull(snapshot.depositText());
        monthlyRentText = SourceText.strippedOrNull(snapshot.monthlyRentText());
    }

    public static LhCatalogSource read(Long id, int order, Instant collectedAt,
            LhCatalogSourceSnapshot snapshot) {
        var source = new LhCatalogSource(order, snapshot);
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
}
