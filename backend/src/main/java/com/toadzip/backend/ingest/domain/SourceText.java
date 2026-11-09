package com.toadzip.backend.ingest.domain;

/** 원천 문자열 값의 공통 정규화 규칙이다. 앞뒤 공백을 지우고, 비어 있으면 값이 없는 것으로 본다. */
public final class SourceText {

    private SourceText() {
    }

    public static String strippedOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
