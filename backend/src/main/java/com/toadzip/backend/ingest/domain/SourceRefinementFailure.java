package com.toadzip.backend.ingest.domain;

/** 실행 보고서에 보존하는 실패 원천. 내부 SQL·스택 대신 관리자가 확인할 설명만 담는다. */
public record SourceRefinementFailure(String sourceKey, String detail) {

    public static SourceRefinementFailure of(String sourceKey, RuntimeException failure) {
        String detail = "원천 정제를 완료하지 못했습니다. 원천을 확인한 뒤 기관 수집을 다시 실행하세요.";
        if (failure instanceof IllegalArgumentException && failure.getMessage() != null) {
            detail = failure.getMessage().replaceAll("(?i)(serviceKey\\s*=\\s*)[^\\s&,]+", "$1[REDACTED]");
        }
        return new SourceRefinementFailure(sourceKey, detail.substring(0, Math.min(detail.length(), 1000)));
    }
}
