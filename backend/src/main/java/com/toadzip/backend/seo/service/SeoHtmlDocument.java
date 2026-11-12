package com.toadzip.backend.seo.service;

import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * 페이지별 head와 본문을 가진 HTML 문서. 공통 head(문자셋·스크립트·스타일·아이콘·대표 이미지)는
 * 프론트 빌드 산출물에 있으므로 Nginx SSI로 끼운다.
 *
 * @param canonicalPath null이면 대표 주소를 쓰지 않는다(없는 페이지).
 */
record SeoHtmlDocument(
        String title,
        String description,
        String canonicalPath,
        boolean indexable,
        List<Object> structuredData,
        String body
) {

    static final String SITE_ORIGIN = "https://bokduckbang.com";
    static final String SITE_NAME = "공공주택 복덕방";
    static final String SHARED_HEAD = "<!--# include virtual=\"/__seo-head\" -->";

    SeoHtmlDocument {
        structuredData = List.copyOf(structuredData);
    }

    String render(ObjectMapper objectMapper) {
        StringBuilder html = new StringBuilder("<!doctype html>\n<html lang=\"ko\">\n<head>\n")
                .append(SHARED_HEAD).append('\n')
                .append("<title>").append(SeoHtml.escape(title)).append("</title>\n");
        meta(html, "name", "description", description);
        robots(html);
        canonical(html);
        meta(html, "property", "og:title", title);
        meta(html, "property", "og:description", description);
        meta(html, "name", "twitter:title", title);
        meta(html, "name", "twitter:description", description);
        structuredData.forEach(data -> structuredData(html, objectMapper, data));
        return html.append("</head>\n<body>\n<div id=\"root\">").append(body).append("</div>\n</body>\n</html>\n")
                .toString();
    }

    private void robots(StringBuilder html) {
        if (indexable) {
            return;
        }
        meta(html, "name", "robots", "noindex, follow");
    }

    private void canonical(StringBuilder html) {
        if (canonicalPath == null) {
            return;
        }
        String url = SITE_ORIGIN + canonicalPath;
        html.append("<link rel=\"canonical\" href=\"").append(SeoHtml.escape(url)).append("\">\n");
        meta(html, "property", "og:url", url);
    }

    private static void meta(StringBuilder html, String attribute, String name, String content) {
        html.append("<meta ").append(attribute).append("=\"").append(name).append("\" content=\"")
                .append(SeoHtml.escape(content)).append("\">\n");
    }

    // JSON 안의 <, >, &를 유니코드 이스케이프해 script를 닫거나 SSI 지시문을 만들 수 없게 한다.
    private static void structuredData(StringBuilder html, ObjectMapper objectMapper, Object data) {
        String json = objectMapper.writeValueAsString(data)
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026");
        html.append("<script type=\"application/ld+json\">").append(json).append("</script>\n");
    }
}
