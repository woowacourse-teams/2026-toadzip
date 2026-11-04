package com.toadzip.backend.ingest.lh.collection.detail.domain;

import com.toadzip.backend.ingest.lh.collection.domain.LhAnnouncementQuerySource;
import com.toadzip.backend.ingest.domain.SourceText;
import java.time.Instant;
import lombok.Getter;

/** 보강이 읽는 LH 공고 상세 원천 행 하나. 저장된 행을 읽어 만든 뒤 바뀌지 않는다. */
@Getter
public class LhAnnouncementDetailSourceView {

    private final Long id;

    private final Integer sourceOrder;
    private final Instant collectedAt;
    private final String panId;
    private final String requestHash;
    private final String datasetType;
    private final String complexName;
    private final String address;
    private final String detailAddress;
    private final String totalUnitCount;
    private final String heatingDescription;
    private final String exclusiveAreaRange;
    private final String expectedMoveInYearMonth;

    private final String guidanceText;

    private final String applicationPeriod;
    private final String applicationBeginDate;
    private final String applicationEndDate;
    private final String winnerAnnouncementDate;
    private final String documentTargetAnnouncementDate;
    private final String documentSubmissionBeginDate;
    private final String documentSubmissionEndDate;
    private final String contractBeginDate;
    private final String contractEndDate;
    private final String receptionAddress;
    private final String receptionDetailAddress;
    private final String operationBegin;
    private final String operationEnd;
    private final String phone;

    private final String receptionGuidance;

    private final String kind;
    private final String name;

    private final String url;

    private final String attachmentComplexName;

    private final String correctionReason;

    private final String etcContents;

    private LhAnnouncementDetailSourceView(
            Long id, int sourceOrder, String panId, String requestHash, Instant collectedAt,
            LhAnnouncementDetailSourceSnapshot snapshot
    ) {
        this.id = id;
        this.sourceOrder = sourceOrder;
        this.panId = SourceText.strippedOrNull(panId);
        this.requestHash = requestHash;
        this.collectedAt = collectedAt;
        datasetType = SourceText.strippedOrNull(snapshot.datasetType());
        complexName = SourceText.strippedOrNull(snapshot.complexName());
        address = SourceText.strippedOrNull(snapshot.address());
        detailAddress = SourceText.strippedOrNull(snapshot.detailAddress());
        totalUnitCount = SourceText.strippedOrNull(snapshot.totalUnitCount());
        heatingDescription = SourceText.strippedOrNull(snapshot.heatingDescription());
        exclusiveAreaRange = SourceText.strippedOrNull(snapshot.exclusiveAreaRange());
        expectedMoveInYearMonth = SourceText.strippedOrNull(snapshot.expectedMoveInYearMonth());
        guidanceText = SourceText.strippedOrNull(snapshot.guidanceText());
        applicationPeriod = SourceText.strippedOrNull(snapshot.applicationPeriod());
        applicationBeginDate = SourceText.strippedOrNull(snapshot.applicationBeginDate());
        applicationEndDate = SourceText.strippedOrNull(snapshot.applicationEndDate());
        winnerAnnouncementDate = SourceText.strippedOrNull(snapshot.winnerAnnouncementDate());
        documentTargetAnnouncementDate = SourceText.strippedOrNull(snapshot.documentTargetAnnouncementDate());
        documentSubmissionBeginDate = SourceText.strippedOrNull(snapshot.documentSubmissionBeginDate());
        documentSubmissionEndDate = SourceText.strippedOrNull(snapshot.documentSubmissionEndDate());
        contractBeginDate = SourceText.strippedOrNull(snapshot.contractBeginDate());
        contractEndDate = SourceText.strippedOrNull(snapshot.contractEndDate());
        receptionAddress = SourceText.strippedOrNull(snapshot.receptionAddress());
        receptionDetailAddress = SourceText.strippedOrNull(snapshot.receptionDetailAddress());
        operationBegin = SourceText.strippedOrNull(snapshot.operationBegin());
        operationEnd = SourceText.strippedOrNull(snapshot.operationEnd());
        phone = SourceText.strippedOrNull(snapshot.phone());
        receptionGuidance = SourceText.strippedOrNull(snapshot.receptionGuidance());
        kind = SourceText.strippedOrNull(snapshot.kind());
        name = SourceText.strippedOrNull(snapshot.name());
        url = SourceText.strippedOrNull(snapshot.url());
        attachmentComplexName = SourceText.strippedOrNull(snapshot.attachmentComplexName());
        correctionReason = SourceText.strippedOrNull(snapshot.correctionReason());
        etcContents = SourceText.strippedOrNull(snapshot.etcContents());
    }

    public static LhAnnouncementDetailSourceView of(LhAnnouncementDetailRow row, LhAnnouncementQuerySource source) {
        return read(row.getId(), row.getSourceOrder(), source.getPanId(), source.getRequestHash(), row.getCollectedAt(),
                row.snapshot());
    }

    public static LhAnnouncementDetailSourceView read(
            Long id, int order, String panId, String requestHash, Instant collectedAt,
            LhAnnouncementDetailSourceSnapshot snapshot
    ) {
        return new LhAnnouncementDetailSourceView(id, order, panId, requestHash, collectedAt, snapshot);
    }
}
