package com.toadzip.backend.ingest.sh.collection.repository;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Repository
public class ShAnnouncementExternalRepository {

    public static final String BOARD_URL =
            "https://www.i-sh.co.kr/main/lay2/program/S1T294C295/www/brd/m_241/";
    public static final String BOARD_TYPES = "1,2,4,8,16,32,64,128,256,512,1024";
    public static final String LIST_URL = BOARD_URL + "list.do?multi_itm_seqs=" + BOARD_TYPES;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final RestClient client;

    public ShAnnouncementExternalRepository(@Qualifier("shAnnouncementRestClient") RestClient client) {
        this.client = client;
    }

    public String fetchList(int page) {
        if (page < 1) {
            throw new IllegalArgumentException("SH 페이지는 1 이상이어야 합니다.");
        }
        // Establish the SH session before submitting the browser's search form.
        if (page == 1) {
            fetch(LIST_URL, null);
        }
        var form = new LinkedMultiValueMap<String, String>();
        for (String name : new String[]{"seq", "multi_itm_seq", "multi_itm_seqsStr", "isRecrnoti",
                "splyTy", "srchFr", "srchTo"}) {
            form.add(name, "");
        }
        form.add("page", Integer.toString(page));
        form.add("itm_seq_1", "1");
        form.add("notType1", "0");
        form.add("annType1", "on");
        form.add("srchTp", "0");
        form.add("srchWord", "모집");
        return fetch(LIST_URL, form);
    }

    public String fetchDetail(String seq) {
        if (seq == null || !seq.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("SH 게시글 식별자가 올바르지 않습니다.");
        }
        return fetch(detailUrl(seq), null);
    }

    public static String detailUrl(String seq) {
        return BOARD_URL + "view.do?seq=" + seq + "&multi_itm_seqs=" + BOARD_TYPES;
    }

    public static String attachmentUrl(String board, String seq, String fileSeq) {
        return "https://www.i-sh.co.kr/main/com/file/innoFD.do?brdId=" + board + "&seq=" + seq
                + "&fileTp=A&fileSeq=" + fileSeq;
    }

    private String fetch(String url, LinkedMultiValueMap<String, String> form) {
        try {
            RestClient.RequestHeadersSpec<?> requestSpec = form == null
                    ? client.get().uri(URI.create(url))
                    : client.post().uri(URI.create(url)).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form);
            return requestSpec.header("Accept", "text/html")
                    .header("User-Agent", "Mozilla/5.0 (compatible; Toadzip/1.0)")
                    .header("Referer", LIST_URL)
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
