package com.toadzip.backend.announcement.dto.response;

import java.time.Instant;

/**
 * 공고문 PDF 추출 작업의 상태.
 *
 * @param jobId 작업 ID
 * @param status PENDING(대기), RUNNING(처리 중), RETRY(일시 오류 뒤 재시도 대기), SUCCEEDED, FAILED
 * @param fileName 올린 PDF 파일 이름
 * @param pdfSha256 올린 PDF 내용의 SHA-256(소문자 16진수)
 * @param attempts 지금까지 시작한 처리 횟수
 * @param createdAt 작업을 넣은 시각
 * @param completedAt 성공하거나 실패한 시각. 진행 중이면 null이다.
 * @param failure 실패 사유. FAILED가 아니면 null이다.
 * @param retryReason 직전 시도의 일시 오류. RETRY가 아니면 null이다. 화면이 재시도를 기다리는 동안 보여 준다.
 * @param extraction 추출 결과. SUCCEEDED가 아니면 null이다.
 */
public record PdfExtractionJobResponse(
        long jobId,
        String status,
        String fileName,
        String pdfSha256,
        int attempts,
        Instant createdAt,
        Instant completedAt,
        Failure failure,
        Failure retryReason,
        AnnouncementPdfExtraction extraction
) {
    /** code는 워커가 정한 고정 값, message는 관리자에게 보여 줄 공개 문구다. */
    public record Failure(String code, String message) {
    }
}
