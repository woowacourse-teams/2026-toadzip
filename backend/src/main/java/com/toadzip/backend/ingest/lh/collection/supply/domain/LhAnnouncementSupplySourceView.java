package com.toadzip.backend.ingest.lh.collection.supply.domain;

import com.toadzip.backend.ingest.lh.collection.domain.LhAnnouncementQuerySource;
import com.toadzip.backend.ingest.domain.SourceText;
import java.time.Instant;
import lombok.Getter;

/** 정제·보강이 읽는 LH 공급행 원천 하나. 저장된 행을 읽어 만든 뒤 바뀌지 않는다. */
@Getter
public class LhAnnouncementSupplySourceView {

    private final Long id;

    private final Integer sourceOrder;
    private final Instant collectedAt;
    private final String panId;
    private final String requestHash;
    private final String complexLabel;
    private final String typeName;
    private final String exclusiveArea;
    private final String supplyArea;
    private final String totalUnitCount;
    private final String suppliedUnitCount;
    private final String depositText;
    private final String monthlyRentText;

    private LhAnnouncementSupplySourceView(
            Long id, int sourceOrder, String panId, String requestHash, Instant collectedAt,
            LhAnnouncementSupplySourceSnapshot snapshot
    ) {
        this.id = id;
        this.sourceOrder = sourceOrder;
        this.panId = SourceText.strippedOrNull(panId);
        this.requestHash = requestHash;
        this.collectedAt = collectedAt;
        complexLabel = SourceText.strippedOrNull(snapshot.complexLabel());
        typeName = SourceText.strippedOrNull(snapshot.typeName());
        exclusiveArea = SourceText.strippedOrNull(snapshot.exclusiveArea());
        supplyArea = SourceText.strippedOrNull(snapshot.supplyArea());
        totalUnitCount = SourceText.strippedOrNull(snapshot.totalUnitCount());
        suppliedUnitCount = SourceText.strippedOrNull(snapshot.suppliedUnitCount());
        depositText = SourceText.strippedOrNull(snapshot.depositText());
        monthlyRentText = SourceText.strippedOrNull(snapshot.monthlyRentText());
    }

    public static LhAnnouncementSupplySourceView of(LhAnnouncementSupplyRow row, LhAnnouncementQuerySource source) {
        return read(row.getId(), row.getSourceOrder(), source.getPanId(), source.getRequestHash(), row.getCollectedAt(),
                row.snapshot());
    }

    public static LhAnnouncementSupplySourceView read(
            Long id, int order, String panId, String requestHash, Instant collectedAt,
            LhAnnouncementSupplySourceSnapshot snapshot
    ) {
        return new LhAnnouncementSupplySourceView(id, order, panId, requestHash, collectedAt, snapshot);
    }
}
