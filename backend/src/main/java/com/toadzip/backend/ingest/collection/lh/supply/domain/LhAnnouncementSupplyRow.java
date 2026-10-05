package com.toadzip.backend.ingest.collection.lh.supply.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuerySource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "lh_announcement_supply_rows", uniqueConstraints = @UniqueConstraint(
        columnNames = {"source_id", "source_order"}))
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementSupplyRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private LhAnnouncementQuerySource source;

    @Column(nullable = false)
    private int sourceOrder;

    private Instant collectedAt;

    @Column(columnDefinition = "text")
    private String complexLabel;

    @Column(columnDefinition = "text")
    private String typeName;

    @Column(columnDefinition = "text")
    private String exclusiveArea;

    @Column(columnDefinition = "text")
    private String supplyArea;

    @Column(columnDefinition = "text")
    private String totalUnitCount;

    @Column(columnDefinition = "text")
    private String suppliedUnitCount;

    @Column(columnDefinition = "text")
    private String depositText;

    @Column(columnDefinition = "text")
    private String monthlyRentText;

    public static LhAnnouncementSupplyRow from(
            LhAnnouncementQuerySource source, int order, LhAnnouncementSupplySourceSnapshot snapshot
    ) {
        LhAnnouncementSupplyRow row = new LhAnnouncementSupplyRow();
        row.source = source;
        row.sourceOrder = order;
        row.collectedAt = source.getCollectedAt();
        row.complexLabel = snapshot.complexLabel();
        row.typeName = snapshot.typeName();
        row.exclusiveArea = snapshot.exclusiveArea();
        row.supplyArea = snapshot.supplyArea();
        row.totalUnitCount = snapshot.totalUnitCount();
        row.suppliedUnitCount = snapshot.suppliedUnitCount();
        row.depositText = snapshot.depositText();
        row.monthlyRentText = snapshot.monthlyRentText();
        return row;
    }

    public LhAnnouncementSupplySourceSnapshot snapshot() {
        return new LhAnnouncementSupplySourceSnapshot(
                complexLabel,
                typeName,
                exclusiveArea,
                supplyArea,
                totalUnitCount,
                suppliedUnitCount,
                depositText,
                monthlyRentText);
    }
}
