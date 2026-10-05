package com.toadzip.backend.ingest.collection.lh.leasecatalog.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

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
@Table(name = "lh_lease_catalog_source_rows", uniqueConstraints = @UniqueConstraint(
        columnNames = {"source_id", "source_order"}
))
@NoArgsConstructor(access = PROTECTED)
public class LhLeaseCatalogSourceRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private LhLeaseCatalogSource source;

    @Column(nullable = false)
    private int sourceOrder;

    private Instant collectedAt;

    @Column(columnDefinition = "text")
    private String areaName;
    @Column(columnDefinition = "text")
    private String supplyTypeName;
    @Column(columnDefinition = "text")
    private String complexLabel;
    @Column(columnDefinition = "text")
    private String complexTotalUnitCount;
    @Column(columnDefinition = "text")
    private String exclusiveArea;
    @Column(columnDefinition = "text")
    private String totalUnitCount;
    @Column(columnDefinition = "text")
    private String depositText;
    @Column(columnDefinition = "text")
    private String monthlyRentText;

    public static LhLeaseCatalogSourceRow from(
            LhLeaseCatalogSource source, int sourceOrder, LhCatalogSourceSnapshot snapshot
    ) {
        if (source == null || sourceOrder < 0) {
            throw new IllegalArgumentException("카탈로그 원천과 응답 순번이 올바르지 않습니다.");
        }
        snapshot.validateIdentifiers();
        LhLeaseCatalogSourceRow row = new LhLeaseCatalogSourceRow();
        row.source = source;
        row.sourceOrder = sourceOrder;
        row.collectedAt = source.getCollectedAt();
        row.areaName = snapshot.areaName();
        row.supplyTypeName = snapshot.supplyTypeName();
        row.complexLabel = snapshot.complexLabel();
        row.complexTotalUnitCount = snapshot.complexTotalUnitCount();
        row.exclusiveArea = snapshot.exclusiveArea();
        row.totalUnitCount = snapshot.totalUnitCount();
        row.depositText = snapshot.depositText();
        row.monthlyRentText = snapshot.monthlyRentText();
        return row;
    }

    public LhCatalogSourceSnapshot snapshot() {
        return new LhCatalogSourceSnapshot(areaName, supplyTypeName, complexLabel, complexTotalUnitCount,
                exclusiveArea, totalUnitCount, depositText, monthlyRentText);
    }
}
