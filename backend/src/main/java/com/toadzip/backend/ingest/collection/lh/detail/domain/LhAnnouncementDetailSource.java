package com.toadzip.backend.ingest.collection.lh.detail.domain;

import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
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
        source.applicationBeginDate = trim(snapshot.applicationBeginDate());
        source.applicationEndDate = trim(snapshot.applicationEndDate());
        source.winnerAnnouncementDate = trim(snapshot.winnerAnnouncementDate());
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
        this.applicationBeginDate = trim(applicationBeginDate);
        this.applicationEndDate = trim(applicationEndDate);
        this.winnerAnnouncementDate = trim(winnerAnnouncementDate);
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

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
