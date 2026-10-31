package com.toadzip.backend.ingest.collection.lh.detail.domain;

import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.domain.SourceText;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LhAnnouncementDetailSource {

    private Long id;

    private Integer sourceOrder;
    private Instant collectedAt;
    private String panId;
    private String requestHash;
    private String datasetType;
    private String complexName;
    private String address;
    private String detailAddress;
    private String totalUnitCount;
    private String heatingDescription;
    private String exclusiveAreaRange;
    private String expectedMoveInYearMonth;

    private String guidanceText;

    private String applicationPeriod;
    private String applicationBeginDate;
    private String applicationEndDate;
    private String winnerAnnouncementDate;
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

    private String receptionGuidance;

    private String kind;
    private String name;

    private String url;

    private String attachmentComplexName;

    private String correctionReason;

    private String etcContents;

    public LhAnnouncementDetailSource(
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
        this.panId = SourceText.strippedOrNull(panId);
        this.datasetType = SourceText.strippedOrNull(datasetType);
        this.complexName = SourceText.strippedOrNull(complexName);
        this.address = SourceText.strippedOrNull(address);
        this.detailAddress = SourceText.strippedOrNull(detailAddress);
        this.totalUnitCount = SourceText.strippedOrNull(totalUnitCount);
        this.heatingDescription = SourceText.strippedOrNull(heatingDescription);
        this.exclusiveAreaRange = SourceText.strippedOrNull(exclusiveAreaRange);
        this.expectedMoveInYearMonth = SourceText.strippedOrNull(expectedMoveInYearMonth);
        this.guidanceText = SourceText.strippedOrNull(guidanceText);
        this.applicationPeriod = SourceText.strippedOrNull(applicationPeriod);
        this.documentTargetAnnouncementDate = SourceText.strippedOrNull(documentTargetAnnouncementDate);
        this.documentSubmissionBeginDate = SourceText.strippedOrNull(documentSubmissionBeginDate);
        this.documentSubmissionEndDate = SourceText.strippedOrNull(documentSubmissionEndDate);
        this.contractBeginDate = SourceText.strippedOrNull(contractBeginDate);
        this.contractEndDate = SourceText.strippedOrNull(contractEndDate);
        this.receptionAddress = SourceText.strippedOrNull(receptionAddress);
        this.receptionDetailAddress = SourceText.strippedOrNull(receptionDetailAddress);
        this.operationBegin = SourceText.strippedOrNull(operationBegin);
        this.operationEnd = SourceText.strippedOrNull(operationEnd);
        this.phone = SourceText.strippedOrNull(phone);
        this.receptionGuidance = SourceText.strippedOrNull(receptionGuidance);
        this.kind = SourceText.strippedOrNull(kind);
        this.name = SourceText.strippedOrNull(name);
        this.url = SourceText.strippedOrNull(url);
        this.attachmentComplexName = SourceText.strippedOrNull(attachmentComplexName);
        this.correctionReason = SourceText.strippedOrNull(correctionReason);
        this.etcContents = SourceText.strippedOrNull(etcContents);
    }

    public static LhAnnouncementDetailSource read(
            Long id, int order, String panId, String requestHash, Instant collectedAt,
            LhAnnouncementDetailSourceSnapshot snapshot
    ) {
        var source = new LhAnnouncementDetailSource(order, panId, snapshot.datasetType(), snapshot.complexName(),
                snapshot.address(), snapshot.detailAddress(), snapshot.totalUnitCount(), snapshot.heatingDescription(),
                snapshot.exclusiveAreaRange(), snapshot.expectedMoveInYearMonth(), snapshot.guidanceText(),
                snapshot.applicationPeriod(), snapshot.documentTargetAnnouncementDate(),
                snapshot.documentSubmissionBeginDate(), snapshot.documentSubmissionEndDate(),
                        snapshot.contractBeginDate(),
                snapshot.contractEndDate(), snapshot.receptionAddress(), snapshot.receptionDetailAddress(),
                snapshot.operationBegin(), snapshot.operationEnd(), snapshot.phone(), snapshot.receptionGuidance(),
                snapshot.kind(), snapshot.name(), snapshot.url(), snapshot.attachmentComplexName(),
                snapshot.correctionReason(), snapshot.etcContents());
        source.id = id;
        source.requestHash = requestHash;
        source.collectedAt = collectedAt;
        source.applicationBeginDate = SourceText.strippedOrNull(snapshot.applicationBeginDate());
        source.applicationEndDate = SourceText.strippedOrNull(snapshot.applicationEndDate());
        source.winnerAnnouncementDate = SourceText.strippedOrNull(snapshot.winnerAnnouncementDate());
        return source;
    }

    public void markCollectedAt(Instant collectedAt) {
        if (collectedAt == null) {
            throw new IllegalArgumentException("수집 시각은 필수입니다.");
        }
        this.collectedAt = collectedAt;
    }

    public void assignScheduleDates(
            String applicationBeginDate, String applicationEndDate, String winnerAnnouncementDate
    ) {
        if (!"SCHEDULE".equals(datasetType)) {
            throw new IllegalStateException("일정 원천에만 접수·당첨자 발표일을 지정할 수 있습니다.");
        }
        this.applicationBeginDate = SourceText.strippedOrNull(applicationBeginDate);
        this.applicationEndDate = SourceText.strippedOrNull(applicationEndDate);
        this.winnerAnnouncementDate = SourceText.strippedOrNull(winnerAnnouncementDate);
    }

    public void assignRequestHash(String requestHash) {
        if (requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException("LH 조회 조건 해시는 필수입니다.");
        }
        this.requestHash = requestHash;
    }

    public boolean hasContent() {
        return Stream.of(
                complexName, address, detailAddress, totalUnitCount, heatingDescription,
                exclusiveAreaRange, expectedMoveInYearMonth, guidanceText, applicationPeriod,
                applicationBeginDate, applicationEndDate, winnerAnnouncementDate,
                documentTargetAnnouncementDate, documentSubmissionBeginDate, documentSubmissionEndDate,
                contractBeginDate, contractEndDate, receptionAddress, receptionDetailAddress,
                operationBegin, operationEnd, phone, receptionGuidance, kind, name, url,
                attachmentComplexName, correctionReason, etcContents
        ).anyMatch(Objects::nonNull);
    }
}
