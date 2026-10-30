package com.toadzip.backend.announcement.dto.response;

import java.util.List;

/**
 * 공고문 PDF 자동 추출 결과. 관리자가 PDF 보정 화면에서 검토한 뒤 반영한다.
 *
 * @param json 프롬프트 응답 형식의 추출 JSON. 근거 쪽 번호는 원본 PDF 기준이다.
 * @param pages 모델에 보낸 원본 쪽 번호(1부터)
 * @param totalPages 원본 PDF의 전체 쪽 수
 * @param warnings 관리자가 확인할 규칙 위반과 쪽 선별 결과
 * @param usage 토큰 사용량. 공급자가 알려주지 않은 값은 null이다.
 */
public record AnnouncementPdfExtraction(
        String json,
        List<Integer> pages,
        int totalPages,
        List<String> warnings,
        Usage usage
) {
    public record Usage(Integer promptTokens, Integer outputTokens, Integer thinkingTokens) {
    }
}
