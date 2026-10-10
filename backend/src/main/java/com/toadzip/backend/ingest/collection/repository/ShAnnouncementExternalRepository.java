package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Repository
public class ShAnnouncementExternalRepository {

    public static final String BOARD_URL =
            "https://www.i-sh.co.kr/app/lay2/program/S48T561C563/www/brd/m_247/";
    public static final String LIST_URL = BOARD_URL + "list.do?multi_itm_seq=2";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final RestClient client;

    public ShAnnouncementExternalRepository(@Qualifier("shAnnouncementRestClient") RestClient client) {
        this.client = client;
    }

    public String fetchList(int page) {
        if (page < 1) {
            throw new IllegalArgumentException("SH 페이지는 1 이상이어야 합니다.");
        }
        return fetch(LIST_URL + "&page=" + page);
    }

    public String fetchDetail(String seq) {
        if (seq == null || !seq.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("SH 게시글 식별자가 올바르지 않습니다.");
        }
        return fetch(detailUrl(seq));
    }

    public static String detailUrl(String seq) {
        return BOARD_URL + "view.do?seq=" + seq + "&multi_itm_seq=2";
    }

    private String fetch(String url) {
        try {
            return client.get().uri(URI.create(url)).header("Accept", "text/html")
                    .header("User-Agent", "Toadzip/1.0 (public housing notice collector)")
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) {
                            throw ExternalDataRequestException.rateLimited("SH 요청이 호출 제한(429)에 도달했습니다.");
                        }
                        if (status >= 500) {
                            throw ExternalDataRequestException.retryable("SH 서버 오류: HTTP " + status);
                        }
                        if (status != 200) {
                            throw new ExternalDataRequestException("SH 요청 실패: HTTP " + status);
                        }
                        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
                            throw new ExternalDataRequestException("SH HTML 응답이 비어 있거나 2 MiB를 초과합니다.");
                        }
                        return new String(bytes, StandardCharsets.UTF_8);
                    });
        }
        catch (ResourceAccessException exception) {
            throw ExternalDataRequestException.retryable("SH 서버에 연결하거나 응답을 읽지 못했습니다.", exception);
        }
    }
}
