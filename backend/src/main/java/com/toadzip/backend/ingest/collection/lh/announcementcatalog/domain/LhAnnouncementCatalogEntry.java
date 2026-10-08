package com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain;

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
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "lh_announcement_catalog_entries")
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementCatalogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(nullable = false, unique = true, length = 200)
    private String sourceKey;

    @Column(columnDefinition = "text")
    private String panId;

    @Column(columnDefinition = "text")
    private String connectionSystemDivisionCode;

    @Column(columnDefinition = "text")
    private String upperAnnouncementTypeCode;

    @Column(columnDefinition = "text")
    private String announcementTypeCode;

    @Column(columnDefinition = "text")
    private String supplyInfoTypeCode;

    @Column(columnDefinition = "text")
    private String announcementName;

    @Column(columnDefinition = "text")
    private String status;

    @Column(columnDefinition = "text")
    private String noticeDate;

    @Column(columnDefinition = "text")
    private String publicationDate;

    @Column(columnDefinition = "text")
    private String closingDate;

    @Column(columnDefinition = "text")
    private String detailUrl;

    @Column(columnDefinition = "text")
    private String mobileDetailUrl;

    @Column(nullable = false, columnDefinition = "text")
    private String rawPayload;

    @Column(columnDefinition = "text")
    private String queryStartDate;

    @Column(columnDefinition = "text")
    private String queryEndDate;

    @Column(nullable = false)
    private Instant changedAt;

    @Column(nullable = false)
    private Instant collectedAt;

    @Column(nullable = false)
    private boolean presentInLatestCatalog;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "last_collection_record_id", nullable = false)
    private SourceCollectionRecord lastCollectionRecord;

    public static LhAnnouncementCatalogEntry create(String sourceKey) {
        LhAnnouncementCatalogEntry source = new LhAnnouncementCatalogEntry();
        source.sourceKey = sourceKey;
        return source;
    }

    public boolean replace(
            LhAnnouncementCatalogRow row, String startDate, String endDate, Instant collectedAt,
            SourceCollectionRecord record
    ) {
        if (!sourceKey.equals(row.snapshot().sourceKey())
                || record.getSource() != CollectionSource.LH_ANNOUNCEMENT_CATALOG
                || record.getStatus() == CollectionStatus.FAILED) {
            throw new IllegalArgumentException("LH 공고 목록 원천과 수집 기록이 일치하지 않습니다.");
        }
        if (this.collectedAt != null && this.collectedAt.isAfter(collectedAt)) {
            throw new IllegalArgumentException("이미 저장된 LH 목록보다 오래된 응답은 반영할 수 없습니다.");
        }
        boolean changed = !presentInLatestCatalog || !snapshot().normalized().equals(row.snapshot().normalized());
        if (changed) {
            changedAt = collectedAt;
        }
        panId = row.snapshot().panId();
        connectionSystemDivisionCode = row.snapshot().connectionSystemDivisionCode();
        upperAnnouncementTypeCode = row.snapshot().upperAnnouncementTypeCode();
        announcementTypeCode = row.snapshot().announcementTypeCode();
        supplyInfoTypeCode = row.snapshot().supplyInfoTypeCode();
        announcementName = row.snapshot().announcementName();
        status = row.snapshot().status();
        noticeDate = row.snapshot().noticeDate();
        publicationDate = row.snapshot().publicationDate();
        closingDate = row.snapshot().closingDate();
        detailUrl = row.snapshot().detailUrl();
        mobileDetailUrl = row.snapshot().mobileDetailUrl();
        rawPayload = row.rawPayload();
        queryStartDate = startDate;
        queryEndDate = endDate;
        this.collectedAt = collectedAt;
        lastCollectionRecord = record;
        presentInLatestCatalog = true;
        return changed;
    }

    public LhAnnouncementCatalogSnapshot snapshot() {
        return new LhAnnouncementCatalogSnapshot(
                panId,
                connectionSystemDivisionCode,
                upperAnnouncementTypeCode,
                announcementTypeCode,
                supplyInfoTypeCode,
                announcementName,
                status,
                noticeDate,
                publicationDate,
                closingDate,
                detailUrl,
                mobileDetailUrl);
    }
}
