package com.toadzip.backend.ingest.lh.collection.leasecatalog.domain;

import com.toadzip.backend.ingest.domain.SourceText;
import java.time.Instant;
import lombok.Getter;

/** 보강·단지 병합이 읽는 LH 임대 카탈로그 원천 하나. 저장된 행을 읽어 만든 뒤 바뀌지 않는다. */
@Getter
public class LhLeaseCatalogSourceView {

    private final Long id;

    private final Integer sourceOrder;
    private final Instant collectedAt;
    private final String areaName;
    private final String supplyTypeName;
    private final String complexLabel;
    private final String complexTotalUnitCount;
    private final String exclusiveArea;
    private final String totalUnitCount;
    private final String depositText;
    private final String monthlyRentText;

    private LhLeaseCatalogSourceView(Long id, int sourceOrder, Instant collectedAt, LhCatalogSourceSnapshot snapshot) {
        this.id = id;
        this.sourceOrder = sourceOrder;
        this.collectedAt = collectedAt;
        areaName = SourceText.strippedOrNull(snapshot.areaName());
        supplyTypeName = SourceText.strippedOrNull(snapshot.supplyTypeName());
        complexLabel = SourceText.strippedOrNull(snapshot.complexLabel());
        complexTotalUnitCount = SourceText.strippedOrNull(snapshot.complexTotalUnitCount());
        exclusiveArea = SourceText.strippedOrNull(snapshot.exclusiveArea());
        totalUnitCount = SourceText.strippedOrNull(snapshot.totalUnitCount());
        depositText = SourceText.strippedOrNull(snapshot.depositText());
        monthlyRentText = SourceText.strippedOrNull(snapshot.monthlyRentText());
    }

    public static LhLeaseCatalogSourceView read(Long id, int order, Instant collectedAt,
            LhCatalogSourceSnapshot snapshot) {
        return new LhLeaseCatalogSourceView(id, order, collectedAt, snapshot);
    }
}
