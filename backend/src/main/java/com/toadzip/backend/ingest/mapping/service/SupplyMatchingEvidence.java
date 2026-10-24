package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.net.URI;

/** 관리자가 저장하는 매칭·연결의 근거(공식 출처 URL과 확인 이유)를 검증한다. */
final class SupplyMatchingEvidence {
    private SupplyMatchingEvidence() {
    }

    static void validate(String sourceUrl, String reason) {
        if (reason == null || reason.isBlank() || sourceUrl == null) {
            throw new InvalidIngestRequestException("저장할 매칭의 공식 출처 URL과 확인 근거를 입력해 주세요.");
        }
        try {
            var uri = URI.create(sourceUrl.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new InvalidIngestRequestException("공식 출처는 올바른 HTTP 또는 HTTPS URL로 입력해 주세요.");
        }
    }
}
