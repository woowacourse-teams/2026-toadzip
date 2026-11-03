package com.toadzip.backend.ingest.gh.collection.repository;

import com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Repository
public class GhAnnouncementExternalRepository {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final RestClient client;

    public GhAnnouncementExternalRepository(@Qualifier("ghAnnouncementRestClient") RestClient client) {
        this.client = client;
    }

    public String fetchList(GhAnnouncementBoard board, int page) {
        if (page < 1) {
            throw new IllegalArgumentException("GH 페이지는 1 이상이어야 합니다.");
        }
        return fetch(board.listUrl(page), board);
    }

    public String fetchDetail(GhAnnouncementBoard board, String pbancNo, String kindCode) {
        if (pbancNo == null || !pbancNo.matches("[1-9][0-9]*") || kindCode == null || !kindCode.matches("[0-9]{2}")) {
            throw new IllegalArgumentException("GH 공고 식별자가 올바르지 않습니다.");
        }
        return fetch(board.detailUrl(pbancNo, kindCode), board);
    }

    /** 주택형 상세공급정보 팝업. 상세 화면이 보내는 폼 값만 그대로 보낸다. */
    public String fetchHousingTypeDetail(GhAnnouncementBoard board, Map<String, String> form) {
        if (!form.keySet().equals(Set.copyOf(GhHousingTypeDetails.REQUEST_FIELDS))
                || !form.getOrDefault("houseTyCd", "").matches("[0-9A-Za-z,\\-]+")) {
            throw new IllegalArgumentException("GH 주택형 상세 요청 값이 올바르지 않습니다.");
        }
        var body = new LinkedMultiValueMap<String, String>();
        form.forEach(body::add);
        return fetch(client.post().uri(URI.create(board.housingTypeDetailUrl()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body), board);
    }

    private String fetch(String url, GhAnnouncementBoard board) {
        return fetch(client.get().uri(URI.create(url)), board);
    }

    private String fetch(RestClient.RequestHeadersSpec<?> spec, GhAnnouncementBoard board) {
        try {
            return spec
                    .header("Accept", "text/html")
                    .header("User-Agent", "Mozilla/5.0 (compatible; Toadzip/1.0)")
                    .header("Referer", board.listUrl())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) {
                            throw ExternalDataRequestException.rateLimited("GH 요청이 호출 제한(429)에 도달했습니다.");
                        }
                        if (status >= 500) {
                            throw ExternalDataRequestException.retryable("GH 서버 오류: HTTP " + status);
                        }
                        if (status != 200) {
                            throw new ExternalDataRequestException("GH 요청 실패: HTTP " + status);
                        }
                        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
                            throw new ExternalDataRequestException("GH HTML 응답이 비어 있거나 2 MiB를 초과합니다.");
                        }
                        return new String(bytes, StandardCharsets.UTF_8);
                    });
        }
        catch (ResourceAccessException exception) {
            throw ExternalDataRequestException.retryable("GH 서버에 연결하거나 응답을 읽지 못했습니다.", exception);
        }
    }
}
