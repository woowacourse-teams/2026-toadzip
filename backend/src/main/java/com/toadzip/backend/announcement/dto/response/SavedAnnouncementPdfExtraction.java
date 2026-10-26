package com.toadzip.backend.announcement.dto.response;

import java.time.Instant;

/**
 * 저장해 둔 공고문 PDF 자동 추출 결과.
 *
 * @param fileName 추출한 PDF 파일 이름
 * @param pdfSha256 추출한 PDF 내용의 SHA-256(소문자 16진수). 화면이 지금 고른 PDF와 같은지 비교한다.
 * @param createdAt 추출한 시각
 * @param extraction 추출 당시 응답
 */
public record SavedAnnouncementPdfExtraction(
        String fileName,
        String pdfSha256,
        Instant createdAt,
        AnnouncementPdfExtraction extraction
) {
}
