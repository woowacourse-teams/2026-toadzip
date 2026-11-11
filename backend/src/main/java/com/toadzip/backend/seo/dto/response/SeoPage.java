package com.toadzip.backend.seo.dto.response;

/**
 * 검색엔진·링크 미리보기용 HTML 응답. 프론트 Nginx가 SSI로 공통 head를 끼운 뒤 그대로 내보낸다.
 * {@code redirectPath}가 있으면 HTML 대신 그 주소로 이동시킨다.
 */
public record SeoPage(int status, String html, String redirectPath) {

    public static SeoPage found(String html) {
        return new SeoPage(200, html, null);
    }

    public static SeoPage notFound(String html) {
        return new SeoPage(404, html, null);
    }

    public static SeoPage redirect(String path) {
        return new SeoPage(308, "", path);
    }
}
