package com.toadzip.backend.ingest.collection.lh.leasecatalog.domain;

import static jakarta.persistence.CascadeType.ALL;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "lh_lease_catalog_source_bundles")
@NoArgsConstructor(access = PROTECTED)
public class LhLeaseCatalogSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(nullable = false, unique = true, length = 20)
    private String scopeKey;

    private Instant collectedAt;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "last_collection_record_id", nullable = false)
    private SourceCollectionRecord lastCollectionRecord;

    @OneToMany(mappedBy = "source", cascade = ALL, orphanRemoval = true)
    @OrderBy("sourceOrder asc")
    private List<LhLeaseCatalogSourceRow> rows = new ArrayList<>();

    public static LhLeaseCatalogSource create() {
        LhLeaseCatalogSource source = new LhLeaseCatalogSource();
        source.scopeKey = "ALL";
        return source;
    }

    public void beginReplacement(Instant collectedAt, SourceCollectionRecord record) {
        if (collectedAt == null || this.collectedAt != null && this.collectedAt.isAfter(collectedAt)) {
            throw new IllegalArgumentException("이미 저장된 카탈로그 원천보다 오래된 수집 응답은 반영할 수 없습니다.");
        }
        if (record.getSource() != CollectionSource.LH_LEASE_CATALOG || record.getStatus() == CollectionStatus.FAILED
                || !scopeKey.equals(record.getRequestParameters().get("scope"))) {
            throw new IllegalArgumentException("카탈로그 원천의 조회 범위와 수집 기록이 일치하지 않습니다.");
        }
        this.collectedAt = collectedAt;
        lastCollectionRecord = record;
        rows.clear();
    }

    public void addResponseRows(List<LhCatalogSourceSnapshot> snapshots) {
        for (int index = 0; index < snapshots.size(); index++) {
            rows.add(LhLeaseCatalogSourceRow.from(this, index, snapshots.get(index)));
        }
    }

    public List<LhLeaseCatalogSourceRow> getRows() {
        return List.copyOf(rows);
    }
}
