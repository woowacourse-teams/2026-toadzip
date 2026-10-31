package com.toadzip.backend.ingest.collection.lh.supply.domain;

import com.toadzip.backend.ingest.domain.SourceText;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LhAnnouncementSupplySource {

    private Long id;

    private Integer sourceOrder;
    private Instant collectedAt;
    private String panId;
    private String requestHash;
    private String complexLabel;
    private String typeName;
    private String exclusiveArea;
    private String supplyArea;
    private String totalUnitCount;
    private String suppliedUnitCount;
    private String depositText;
    private String monthlyRentText;

    public LhAnnouncementSupplySource(int sourceOrder, String panId, LhAnnouncementSupplySourceSnapshot snapshot) {
        this.sourceOrder = sourceOrder;
        this.panId = SourceText.strippedOrNull(panId);
        complexLabel = SourceText.strippedOrNull(snapshot.complexLabel());
        typeName = SourceText.strippedOrNull(snapshot.typeName());
        exclusiveArea = SourceText.strippedOrNull(snapshot.exclusiveArea());
        supplyArea = SourceText.strippedOrNull(snapshot.supplyArea());
        totalUnitCount = SourceText.strippedOrNull(snapshot.totalUnitCount());
        suppliedUnitCount = SourceText.strippedOrNull(snapshot.suppliedUnitCount());
        depositText = SourceText.strippedOrNull(snapshot.depositText());
        monthlyRentText = SourceText.strippedOrNull(snapshot.monthlyRentText());
    }

    public static LhAnnouncementSupplySource read(
            Long id, int order, String panId, String requestHash, Instant collectedAt,
            LhAnnouncementSupplySourceSnapshot snapshot
    ) {
        var source = new LhAnnouncementSupplySource(order, panId, snapshot);
        source.id = id;
        source.requestHash = requestHash;
        source.collectedAt = collectedAt;
        return source;
    }

    public void markCollectedAt(Instant collectedAt) {
        if (collectedAt == null) {
            throw new IllegalArgumentException("수집 시각은 필수입니다.");
        }
        this.collectedAt = collectedAt;
    }

    public void assignRequestHash(String requestHash) {
        if (requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException("LH 조회 조건 해시는 필수입니다.");
        }
        this.requestHash = requestHash;
    }
}
