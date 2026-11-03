package com.toadzip.backend.announcement.domain;

/**
 * 관리자가 PDF 보정 화면에서 임시저장한 작업 내용.
 *
 * @param payload 화면이 만든 작업 상태 JSON 객체. 서버는 해석하지 않는다.
 * @param baseSnapshotToken 작업을 시작할 때 본 PDF 보정 작업 공간의 토큰
 * @param pdfSha256 고른 공고문 PDF의 SHA-256. 아직 고르지 않았으면 null이다.
 * @param extractionId 작업에 쓴 PDF 추출 작업 ID. 없으면 null이다.
 */
public record CorrectionDraftContent(String payload, String baseSnapshotToken, String pdfSha256, Long extractionId) {
}
