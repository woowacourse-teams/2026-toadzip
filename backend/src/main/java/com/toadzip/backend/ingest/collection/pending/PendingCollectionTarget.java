package com.toadzip.backend.ingest.collection.pending;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 발견했지만 원천 저장을 완료하지 못한 요청. 성공 이력은 기존 원천·수집 이력에 둔다. */
@Entity
@Table(name = "pending_collection_targets",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source", "target_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingCollectionTarget {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50)
    private ExternalDataSource source;
    @Column(name = "target_key", nullable = false, length = 500)
    private String targetKey;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(nullable = false)
    private int listPage;

    PendingCollectionTarget(ExternalDataSource source, String targetKey, String payload, int listPage) {
        this.source = source;
        this.targetKey = targetKey;
        update(payload, listPage);
    }

    void update(String payload, int listPage) {
        this.payload = payload;
        this.listPage = listPage;
    }
}
