package com.toadzip.backend.ingest.enrichment.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.failure.domain.IngestFailure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "lh_announcement_enrichment_failures")
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementEnrichmentFailure extends IngestFailure<LhAnnouncementEnrichmentFailure> {

    private String sourceAnnouncementIdentifier;

    private String panId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private LhAnnouncementEnrichmentFailureReason reason;

    private LhAnnouncementEnrichmentFailure(
            String sourceKey,
            String sourceAnnouncementIdentifier,
            String panId,
            LhAnnouncementEnrichmentFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        super(sourceKey, reason, detail, occurredAt);
        this.sourceAnnouncementIdentifier = sourceAnnouncementIdentifier;
        this.panId = panId;
        this.reason = reason;
    }

    @Override
    protected void updateObservedDetails(LhAnnouncementEnrichmentFailure observed) {
        sourceAnnouncementIdentifier = observed.sourceAnnouncementIdentifier;
        panId = observed.panId;
    }

    public static LhAnnouncementEnrichmentFailure create(
            String sourceKey,
            String sourceAnnouncementIdentifier,
            String panId,
            LhAnnouncementEnrichmentFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        return new LhAnnouncementEnrichmentFailure(
                sourceKey, sourceAnnouncementIdentifier, panId, reason, detail, occurredAt
        );
    }
}
