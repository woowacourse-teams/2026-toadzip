package com.toadzip.backend.ingest.collection.lh.detail.domain;

import java.util.List;
import java.util.stream.Stream;

public record LhAnnouncementDetailSourceSnapshot(
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
        String applicationBeginDate,
        String applicationEndDate,
        String winnerAnnouncementDate,
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
    public void validateContent() {
        if (!List.of("ETC_INFO", "COMPLEX", "SCHEDULE", "RECEPTION", "ANNOUNCEMENT_FILE", "COMPLEX_IMAGE")
                .contains(datasetType)) {
            throw new IllegalArgumentException("알 수 없는 LH 상세 dataset입니다.");
        }
        boolean hasContent = Stream.of(
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
                etcContents)
                .anyMatch(value -> value != null && !value.isBlank());
        if (!hasContent) {
            throw new IllegalArgumentException("LH 상세 응답에 내용 없는 행이 있습니다.");
        }
    }
}
