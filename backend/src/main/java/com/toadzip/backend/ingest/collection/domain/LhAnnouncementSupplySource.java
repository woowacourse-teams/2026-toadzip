package com.toadzip.backend.ingest.collection.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "lh_announcement_supply_source",
        uniqueConstraints = @UniqueConstraint(columnNames = {"pan_id", "source_order"})
)
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementSupplySource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer sourceOrder;
    private Instant collectedAt;
    private String panId;
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
