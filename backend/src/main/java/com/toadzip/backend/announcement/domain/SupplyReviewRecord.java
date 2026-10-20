package com.toadzip.backend.announcement.domain;

import static lombok.AccessLevel.PROTECTED;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "admin_supply_reviews")
@NoArgsConstructor(access = PROTECTED)
public class SupplyReviewRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private long announcementId;
    @Column(nullable = false, length = 64) private String target;
    @Column(nullable = false, length = 20) private String outcome;
    @Column(nullable = false, columnDefinition = "text") private String checkedValues;
    @Column(nullable = false, columnDefinition = "text") private String evidence;
    @Column(nullable = false, length = 2000) private String note;
    @Column(nullable = false, length = 2048) private String sourceUrl;
    @Column(length = 100) private String sourcePage;
    @Column(nullable = false) private String actor;
    @Column(nullable = false) private Instant reviewedAt;

    public SupplyReviewRecord(long announcementId, String target, String outcome, String values,
            String evidence, String note, String sourceUrl, String sourcePage, String actor) {
        this.announcementId = announcementId;
        this.target = target;
        this.outcome = outcome;
        this.checkedValues = values;
        this.evidence = evidence;
        this.note = note;
        this.sourceUrl = sourceUrl;
        this.sourcePage = sourcePage;
        this.actor = actor;
        this.reviewedAt = Instant.now();
    }
}
