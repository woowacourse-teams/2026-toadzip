package com.toadzip.backend.seo.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 접수 기간 표기. 제목에는 짧게(11.16~11.17), 본문에는 연도까지 쓴다. */
final class SeoAnnouncementPeriod {

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM.dd");

    private SeoAnnouncementPeriod() {
    }

    static String text(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return "";
        }
        if (start.equals(end)) {
            return SeoHtml.date(start) + " 접수";
        }
        return SeoHtml.date(start) + "~" + SeoHtml.date(end) + " 접수";
    }

    static String shortText(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return "";
        }
        if (start.equals(end)) {
            return SHORT.format(start) + " 접수";
        }
        return SHORT.format(start) + "~" + SHORT.format(end) + " 접수";
    }
}
