package com.toadzip.backend.ingest.collection.lh.supply.domain;

import com.toadzip.backend.ingest.collection.lh.supply.domain.projection.LhAnnouncementSupplySourceSnapshot;
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
        this.panId = trim(panId);
        complexLabel = trim(snapshot.complexLabel());
        typeName = trim(snapshot.typeName());
        exclusiveArea = trim(snapshot.exclusiveArea());
        supplyArea = trim(snapshot.supplyArea());
        totalUnitCount = trim(snapshot.totalUnitCount());
        suppliedUnitCount = trim(snapshot.suppliedUnitCount());
        depositText = trim(snapshot.depositText());
        monthlyRentText = trim(snapshot.monthlyRentText());
    }

    public static LhAnnouncementSupplySource read(
            Long id, int order, String panId, String requestHash, Instant collectedAt,
            com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot snapshot
    ) {
        var normalizedSnapshot = new LhAnnouncementSupplySourceSnapshot(snapshot.complexLabel(), snapshot.typeName(),
                snapshot.exclusiveArea(), snapshot.supplyArea(), snapshot.totalUnitCount(),
                        snapshot.suppliedUnitCount(),
                snapshot.depositText(), snapshot.monthlyRentText());
        var source = new LhAnnouncementSupplySource(order, panId, normalizedSnapshot);
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

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
