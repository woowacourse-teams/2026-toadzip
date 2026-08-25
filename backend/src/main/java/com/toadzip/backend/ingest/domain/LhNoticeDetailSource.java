package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
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
        name = "lh_notice_detail_source",
        uniqueConstraints = @UniqueConstraint(columnNames = {"pan_id", "source_order", "dataset_type"})
)
@NoArgsConstructor(access = PROTECTED)
public class LhNoticeDetailSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer sourceOrder;
    private Instant collectedAt;
    private String panId;
    private String datasetType;
    private String complexName;
    private String address;
    private String detailAddress;
    private String totalUnitCount;
    private String heatingDescription;
    private String exclusiveAreaRange;
    private String expectedMoveInYearMonth;

    @Column(length = 4000)
    private String guidanceText;

    private String applicationPeriod;
    private String documentTargetAnnouncementDate;
    private String documentSubmissionBeginDate;
    private String documentSubmissionEndDate;
    private String contractBeginDate;
    private String contractEndDate;
    private String receptionAddress;
    private String receptionDetailAddress;
    private String operationBegin;
    private String operationEnd;
    private String phone;

    @Column(length = 4000)
    private String receptionGuidance;

    private String kind;
    private String name;

    @Column(length = 1000)
    private String url;

    private String attachmentComplexName;

    @Column(length = 4000)
    private String correctionReason;

    @Column(length = 4000)
    private String etcContents;

    public LhNoticeDetailSource(
            int sourceOrder,
            String panId,
            String datasetType,
            String complexName,
            String address,
            String detailAddress,
            String totalUnitCount,
            String heatingDescription,
            String exclusiveAreaRange,
            String expectedMoveInYearMonth,
            String guidanceText,
            String applicationPeriod,
            String documentTargetAnnouncementDate,
            String documentSubmissionBeginDate,
            String documentSubmissionEndDate,
            String contractBeginDate,
            String contractEndDate,
            String receptionAddress,
            String receptionDetailAddress,
            String operationBegin,
            String operationEnd,
            String phone,
            String receptionGuidance,
            String kind,
            String name,
            String url,
            String attachmentComplexName,
            String correctionReason,
            String etcContents
    ) {
        this.sourceOrder = sourceOrder;
        this.panId = trim(panId);
        this.datasetType = trim(datasetType);
        this.complexName = trim(complexName);
        this.address = trim(address);
        this.detailAddress = trim(detailAddress);
        this.totalUnitCount = trim(totalUnitCount);
        this.heatingDescription = trim(heatingDescription);
        this.exclusiveAreaRange = trim(exclusiveAreaRange);
        this.expectedMoveInYearMonth = trim(expectedMoveInYearMonth);
        this.guidanceText = trim(guidanceText);
        this.applicationPeriod = trim(applicationPeriod);
        this.documentTargetAnnouncementDate = trim(documentTargetAnnouncementDate);
        this.documentSubmissionBeginDate = trim(documentSubmissionBeginDate);
        this.documentSubmissionEndDate = trim(documentSubmissionEndDate);
        this.contractBeginDate = trim(contractBeginDate);
        this.contractEndDate = trim(contractEndDate);
        this.receptionAddress = trim(receptionAddress);
        this.receptionDetailAddress = trim(receptionDetailAddress);
        this.operationBegin = trim(operationBegin);
        this.operationEnd = trim(operationEnd);
        this.phone = trim(phone);
        this.receptionGuidance = trim(receptionGuidance);
        this.kind = trim(kind);
        this.name = trim(name);
        this.url = trim(url);
        this.attachmentComplexName = trim(attachmentComplexName);
        this.correctionReason = trim(correctionReason);
        this.etcContents = trim(etcContents);
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
