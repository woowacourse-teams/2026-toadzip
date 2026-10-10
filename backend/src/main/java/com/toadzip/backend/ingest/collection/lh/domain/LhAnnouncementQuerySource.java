package com.toadzip.backend.ingest.collection.lh.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "lh_announcement_query_sources", uniqueConstraints = @UniqueConstraint(
        columnNames = {"source", "query_hash"}))
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementQuerySource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private CollectionSource source;

    @Column(nullable = false, length = 100)
    private String panId;

    @Column(nullable = false, length = 64)
    private String queryHash;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, columnDefinition = "text")
    private String requestDescription;

    @ElementCollection
    @CollectionTable(name = "lh_announcement_query_parameters", joinColumns = @JoinColumn(name = "source_id"))
    @MapKeyColumn(name = "parameter_name", length = 100)
    @Column(name = "parameter_value", nullable = false, columnDefinition = "text")
    private Map<String, String> requestParameters = new HashMap<>();

    private Instant collectedAt;

    @Column(nullable = false)
    private boolean verifiedEmpty;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "last_collection_record_id", nullable = false)
    private SourceCollectionRecord lastCollectionRecord;

    public static LhAnnouncementQuerySource create(CollectionSource collectionSource, LhAnnouncementQuery query) {
        if (collectionSource != CollectionSource.LH_ANNOUNCEMENT_SUPPLY
                && collectionSource != CollectionSource.LH_ANNOUNCEMENT_DETAIL || query == null) {
            throw new IllegalArgumentException("LH 공고 수집 조건이 올바르지 않습니다.");
        }
        LhAnnouncementQuerySource source = new LhAnnouncementQuerySource();
        source.source = collectionSource;
        source.panId = query.panId();
        source.queryHash = query.identityHash();
        return source;
    }

    public void replace(
            CollectionSource requestSource, LhAnnouncementQuery query, String requestHash, String requestDescription,
            Map<String, String> requestParameters, Instant collectedAt,
            boolean verifiedEmpty, SourceCollectionRecord record
    ) {
        if (source != requestSource || !queryHash.equals(query.identityHash())
                || record.getSource() != source || record.getStatus() == CollectionStatus.FAILED) {
            throw new IllegalArgumentException("LH 원천의 조회 조건과 수집 기록이 일치하지 않습니다.");
        }
        if (collectedAt == null || this.collectedAt != null && this.collectedAt.isAfter(collectedAt)) {
            throw new IllegalArgumentException("이미 저장된 LH 원천보다 오래된 응답은 반영할 수 없습니다.");
        }
        this.collectedAt = collectedAt;
        this.verifiedEmpty = verifiedEmpty;
        this.requestHash = requestHash;
        this.requestDescription = requestDescription;
        this.requestParameters.clear();
        this.requestParameters.putAll(requestParameters);
        lastCollectionRecord = record;
    }

    public Map<String, String> getRequestParameters() {
        return Map.copyOf(requestParameters);
    }
}
