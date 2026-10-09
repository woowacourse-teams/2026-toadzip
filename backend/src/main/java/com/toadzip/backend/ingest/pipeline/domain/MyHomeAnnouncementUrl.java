package com.toadzip.backend.ingest.pipeline.domain;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public record MyHomeAnnouncementUrl(String announcementIdentifier) {

    private static final String DETAIL_PATH = "/hws/portal/sch/selectRsdtRcritNtcDetailView.do";

    public static MyHomeAnnouncementUrl parse(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 2048) {
            throw new IllegalArgumentException("마이홈 공고 URL을 입력해 주세요. 최대 2048자까지 입력할 수 있습니다.");
        }
        URI uri = URI.create(value.strip());
        boolean supportedScheme = "https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme());
        if (!supportedScheme || !"www.myhome.go.kr".equalsIgnoreCase(uri.getHost())
                || !DETAIL_PATH.equals(uri.getRawPath()) || uri.getUserInfo() != null
                || uri.getPort() != -1 || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("마이홈(www.myhome.go.kr) 공고 상세 URL만 등록할 수 있습니다.");
        }
        List<String> identifiers = identifiers(uri.getRawQuery());
        if (identifiers.size() != 1 || !identifiers.getFirst().matches("[0-9]{1,100}")) {
            throw new IllegalArgumentException("공고 URL에는 숫자로 된 pblancId가 정확히 하나 있어야 합니다.");
        }
        return new MyHomeAnnouncementUrl(identifiers.getFirst());
    }

    private static List<String> identifiers(String query) {
        List<String> identifiers = new ArrayList<>();
        if (query == null) {
            return identifiers;
        }
        for (String parameter : query.split("&", -1)) {
            String[] parts = parameter.split("=", 2);
            if ("pblancId".equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                if (parts.length != 2) {
                    identifiers.add("");
                    continue;
                }
                identifiers.add(URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return identifiers;
    }
}
