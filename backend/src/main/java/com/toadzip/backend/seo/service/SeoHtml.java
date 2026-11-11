package com.toadzip.backend.seo.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.web.util.HtmlUtils;

/** 본문 HTML 조각. 모든 값은 이스케이프해 SSI 지시문(`<!--#`)이나 태그가 섞이지 않게 한다. */
final class SeoHtml {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final StringBuilder html = new StringBuilder();

    static String escape(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8");
    }

    static String date(LocalDate date) {
        return DATE.format(date);
    }

    SeoHtml open(String tag) {
        html.append('<').append(tag).append('>');
        return this;
    }

    SeoHtml close(String tag) {
        html.append("</").append(tag).append('>');
        return this;
    }

    SeoHtml element(String tag, String text) {
        return open(tag).text(text).close(tag);
    }

    SeoHtml text(String text) {
        html.append(escape(text));
        return this;
    }

    SeoHtml link(String href, String text) {
        html.append("<a href=\"").append(escape(href)).append("\">").append(escape(text)).append("</a>");
        return this;
    }

    SeoHtml listLink(String href, String text) {
        return open("li").link(href, text).close("li");
    }

    /** 항목 이름과 값. 값이 비어 있으면 쓰지 않는다. */
    SeoHtml fact(String name, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        return element("dt", name).element("dd", value);
    }

    String build() {
        return html.toString();
    }
}
