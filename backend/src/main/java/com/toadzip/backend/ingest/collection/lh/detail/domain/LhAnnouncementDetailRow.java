package com.toadzip.backend.ingest.collection.lh.detail.domain;

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
@Table(name = "lh_announcement_detail_rows", uniqueConstraints = @UniqueConstraint(
        columnNames = {"source_id", "source_order"}))
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementDetailRow {

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
    private String datasetType;

    @Column(columnDefinition = "text")
    private String complexName;

    @Column(columnDefinition = "text")
    private String address;

    @Column(columnDefinition = "text")
    private String detailAddress;

    @Column(columnDefinition = "text")
    private String totalUnitCount;

    @Column(columnDefinition = "text")
    private String heatingDescription;

    @Column(columnDefinition = "text")
    private String exclusiveAreaRange;

    @Column(columnDefinition = "text")
    private String expectedMoveInYearMonth;

    @Column(columnDefinition = "text")
    private String guidanceText;

    @Column(columnDefinition = "text")
    private String applicationPeriod;

    @Column(columnDefinition = "text")
    private String applicationBeginDate;

    @Column(columnDefinition = "text")
    private String applicationEndDate;

    @Column(columnDefinition = "text")
    private String winnerAnnouncementDate;

    @Column(columnDefinition = "text")
    private String documentTargetAnnouncementDate;

    @Column(columnDefinition = "text")
    private String documentSubmissionBeginDate;

    @Column(columnDefinition = "text")
    private String documentSubmissionEndDate;

    @Column(columnDefinition = "text")
    private String contractBeginDate;

    @Column(columnDefinition = "text")
    private String contractEndDate;

    @Column(columnDefinition = "text")
    private String receptionAddress;

    @Column(columnDefinition = "text")
    private String receptionDetailAddress;

    @Column(columnDefinition = "text")
    private String operationBegin;

    @Column(columnDefinition = "text")
    private String operationEnd;

    @Column(columnDefinition = "text")
    private String phone;

    @Column(columnDefinition = "text")
    private String receptionGuidance;

    @Column(columnDefinition = "text")
    private String kind;

    @Column(columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String url;

    @Column(columnDefinition = "text")
    private String attachmentComplexName;

    @Column(columnDefinition = "text")
    private String correctionReason;

    @Column(columnDefinition = "text")
    private String etcContents;

    public static LhAnnouncementDetailRow from(
            LhAnnouncementQuerySource source, int order, LhAnnouncementDetailSourceSnapshot snapshot
    ) {
        LhAnnouncementDetailRow row = new LhAnnouncementDetailRow();
        row.source = source;
        row.sourceOrder = order;
        row.collectedAt = source.getCollectedAt();
        row.datasetType = snapshot.datasetType();
        row.complexName = snapshot.complexName();
        row.address = snapshot.address();
        row.detailAddress = snapshot.detailAddress();
        row.totalUnitCount = snapshot.totalUnitCount();
        row.heatingDescription = snapshot.heatingDescription();
        row.exclusiveAreaRange = snapshot.exclusiveAreaRange();
        row.expectedMoveInYearMonth = snapshot.expectedMoveInYearMonth();
        row.guidanceText = snapshot.guidanceText();
        row.applicationPeriod = snapshot.applicationPeriod();
        row.applicationBeginDate = snapshot.applicationBeginDate();
        row.applicationEndDate = snapshot.applicationEndDate();
        row.winnerAnnouncementDate = snapshot.winnerAnnouncementDate();
        row.documentTargetAnnouncementDate = snapshot.documentTargetAnnouncementDate();
        row.documentSubmissionBeginDate = snapshot.documentSubmissionBeginDate();
        row.documentSubmissionEndDate = snapshot.documentSubmissionEndDate();
        row.contractBeginDate = snapshot.contractBeginDate();
        row.contractEndDate = snapshot.contractEndDate();
        row.receptionAddress = snapshot.receptionAddress();
        row.receptionDetailAddress = snapshot.receptionDetailAddress();
        row.operationBegin = snapshot.operationBegin();
        row.operationEnd = snapshot.operationEnd();
        row.phone = snapshot.phone();
        row.receptionGuidance = snapshot.receptionGuidance();
        row.kind = snapshot.kind();
        row.name = snapshot.name();
        row.url = snapshot.url();
        row.attachmentComplexName = snapshot.attachmentComplexName();
        row.correctionReason = snapshot.correctionReason();
        row.etcContents = snapshot.etcContents();
        return row;
    }

    public LhAnnouncementDetailSourceSnapshot snapshot() {
        return new LhAnnouncementDetailSourceSnapshot(
                datasetType,
                complexName,
                address,
                detailAddress,
                totalUnitCount,
                heatingDescription,
                exclusiveAreaRange,
                expectedMoveInYearMonth,
                guidanceText,
                applicationPeriod,
                applicationBeginDate,
                applicationEndDate,
                winnerAnnouncementDate,
                documentTargetAnnouncementDate,
                documentSubmissionBeginDate,
                documentSubmissionEndDate,
                contractBeginDate,
                contractEndDate,
                receptionAddress,
                receptionDetailAddress,
                operationBegin,
                operationEnd,
                phone,
                receptionGuidance,
                kind,
                name,
                url,
                attachmentComplexName,
                correctionReason,
                etcContents);
    }
}
