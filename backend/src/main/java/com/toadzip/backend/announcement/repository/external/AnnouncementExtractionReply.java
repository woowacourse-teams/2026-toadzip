package com.toadzip.backend.announcement.repository.external;

/** AI 모델이 돌려준 추출 JSON과 토큰 사용량. */
public record AnnouncementExtractionReply(String json, Usage usage) {

    /** 생각 토큰은 전체에서 입력·출력을 뺀 값이라 공급자와 관계없이 같은 방식으로 센다. */
    public record Usage(Integer promptTokens, Integer outputTokens, Integer thinkingTokens) {
    }
}
