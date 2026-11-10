package com.toadzip.backend.seo.service;

import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.SupplyComplexResponse;
import com.toadzip.backend.announcement.dto.response.SupplyRowResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 공고 상세 페이지. 제목은 "인천 남동구 국민임대 예비입주자 모집 (11.16~11.17 접수)"처럼 지역·유형·접수 기간을,
 * 설명은 숫자 없이 공고 이름과 안내를 담는다. 목록에서 빠진 공고(취소·후속 공고가 있는 공고)는 색인하지 않는다.
 */
final class AnnouncementSeoPage {

    private final AnnouncementDetailResponse announcement;
    private final boolean listed;

    AnnouncementSeoPage(AnnouncementDetailResponse announcement, boolean listed) {
        this.announcement = announcement;
        this.listed = listed;
    }

    SeoHtmlDocument document() {
        String path = "/announcements/" + announcement.announcementId();
        SeoBreadcrumb breadcrumb = new SeoBreadcrumb().add(announcement.title(), path);
        return new SeoHtmlDocument(
                title(),
                SeoLabels.withoutNoticeMark(announcement.title())
                        + "의 공급 단지와 신청 일정을 복덕방 지도에서 확인하고, 비슷한 새 공고 알림을 받아 보세요.",
                path,
                listed,
                List.of(breadcrumb.structuredData()),
                body(breadcrumb));
    }

    private String title() {
        List<String> words = new ArrayList<>();
        region().ifPresent(words::add);
        SeoLabels.knownRentalType(name(announcement.rentalType())).ifPresent(words::add);
        SeoLabels.recruitmentType(name(announcement.recruitmentType())).ifPresent(words::add);
        if (words.isEmpty()) {
            return announcement.title() + " | " + SeoHtmlDocument.SITE_NAME;
        }
        return String.join(" ", words) + " 모집" + titleStatus() + " | " + SeoHtmlDocument.SITE_NAME;
    }

    private String titleStatus() {
        String status = name(announcement.applicationStatus());
        if ("CANCELLED".equals(status)) {
            return " (공고 취소)";
        }
        if ("CLOSED".equals(status)) {
            return " (접수 마감)";
        }
        String period = SeoAnnouncementPeriod.shortText(
                announcement.applicationStartAt(), announcement.applicationEndAt());
        if (period.isEmpty()) {
            return "";
        }
        return " (" + period + ")";
    }

    private Optional<String> region() {
        List<String> regions = announcement.regionNames();
        if (regions.isEmpty()) {
            return Optional.empty();
        }
        String first = SeoLabels.shortRegionName(regions.getFirst());
        if (regions.size() == 1) {
            return Optional.of(first);
        }
        return Optional.of(first + " 외 " + (regions.size() - 1) + "곳");
    }

    private String body(SeoBreadcrumb breadcrumb) {
        SeoHtml html = new SeoHtml().open("main");
        breadcrumb.appendTo(html);
        html.element("h1", announcement.title()).open("dl")
                .fact("상태", SeoLabels.applicationStatus(name(announcement.applicationStatus())).orElse(null))
                .fact("공급 기관", agencyName())
                .fact("공급 유형", SeoLabels.knownRentalType(name(announcement.rentalType())).orElse(null))
                .fact("지역", String.join(", ", announcement.regionNames()))
                .fact("공고일", date(announcement.publishedAt()))
                .fact("접수 기간", SeoAnnouncementPeriod.text(
                        announcement.applicationStartAt(), announcement.applicationEndAt()))
                .fact("당첨자 발표", date(announcement.winnerAnnouncementAt()))
                .close("dl");
        complexes(html);
        source(html);
        html.open("p").link("/explore", "지도에서 비슷한 공고 둘러보기").close("p");
        return html.close("main").build();
    }

    private void complexes(SeoHtml html) {
        Map<Long, String> complexes = new LinkedHashMap<>();
        announcement.supplyRows().stream()
                .map(SupplyRowResponse::complex)
                .filter(Objects::nonNull)
                .forEach(complex -> complexes.putIfAbsent(complex.complexId(), complexName(complex)));
        if (complexes.isEmpty()) {
            return;
        }
        html.element("h2", "공급 단지").open("ul");
        complexes.forEach((complexId, complexName) -> html.listLink("/complexes/" + complexId, complexName));
        html.close("ul");
    }

    private void source(SeoHtml html) {
        String url = announcement.documentLinkUrl();
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return;
        }
        html.open("p").link(url, "공고 원문 보기").close("p");
    }

    private static String complexName(SupplyComplexResponse complex) {
        return Optional.ofNullable(complex.name()).filter(name -> !name.isBlank())
                .orElse("단지 " + complex.complexId());
    }

    private String agencyName() {
        if (announcement.agency() == null) {
            return null;
        }
        return SeoLabels.knownAgency(announcement.agency().name());
    }

    private static String date(LocalDate date) {
        return Optional.ofNullable(date).map(SeoHtml::date).orElse(null);
    }

    private static String name(Enum<?> value) {
        return Optional.ofNullable(value).map(Enum::name).orElse(null);
    }
}
