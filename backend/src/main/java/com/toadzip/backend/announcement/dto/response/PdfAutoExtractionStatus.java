package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import java.time.Instant;

/**
 * 운영 홈의 자동 추출 카드. updatedBy·updatedAt은 관리자가 바꾼 적이 없으면 null이다(환경 변수 기본값).
 * notExtracted는 켜면 차례로 유료 추출될 마감 전 '추출 전' 공고 수, inFlight는 진행 중인 서버(BATCH) 작업 수다.
 */
public record PdfAutoExtractionStatus(boolean enabled, String updatedBy, Instant updatedAt,
        PublicationMode publicationMode, long notExtracted, long inFlight, int maxInFlight, Instant asOf) {
}
