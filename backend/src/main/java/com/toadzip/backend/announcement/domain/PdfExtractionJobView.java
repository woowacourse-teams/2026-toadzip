package com.toadzip.backend.announcement.domain;

import java.time.Instant;

/** PDF 내용(최대 30MB)을 빼고 읽는 추출 작업 상태. 화면이 몇 초마다 다시 묻기 때문에 PDF를 함께 읽지 않는다. */
public record PdfExtractionJobView(
        long id,
        PdfExtractionJobStatus status,
        String fileName,
        String pdfSha256,
        int attempts,
        Instant createdAt,
        Instant completedAt,
        String errorCode,
        String errorMessage,
        String result
) {

    /** 방금 넣은 작업. */
    public static PdfExtractionJobView of(PdfExtractionJob job) {
        return new PdfExtractionJobView(job.getId(), job.getStatus(), job.getFileName(), job.getPdfSha256(),
                job.getAttempts(), job.getCreatedAt(), job.getCompletedAt(), job.getErrorCode(),
                job.getErrorMessage(), job.getResult());
    }
}
