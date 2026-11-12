package com.toadzip.backend.seo.service;

import com.toadzip.backend.housing.dto.response.CurrentAnnouncementResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexAddressResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexDetailResponse;
import com.toadzip.backend.housing.dto.response.HousingTypeDetailResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 단지 상세 페이지. 제목에 지역과 공급 유형을, 설명에는 숫자 없이 단지 이름과 안내를 담는다. */
final class ComplexSeoPage {

    private final HousingComplexDetailResponse complex;
    private final Optional<String> districtCode;

    ComplexSeoPage(HousingComplexDetailResponse complex, Optional<String> districtCode) {
        this.complex = complex;
        this.districtCode = districtCode;
    }

    SeoHtmlDocument document() {
        String path = "/complexes/" + complex.complexId();
        SeoBreadcrumb breadcrumb = breadcrumb(path);
        return new SeoHtmlDocument(
                title(),
                complex.name() + "의 주택형과 모집공고를 복덕방 지도에서 확인하고, 새 공고 알림을 받아 보세요.",
                path,
                true,
                List.of(residence(path), breadcrumb.structuredData()),
                body(breadcrumb));
    }

    private String title() {
        List<String> context = new ArrayList<>();
        regionName().map(SeoLabels::shortRegionName).ifPresent(context::add);
        // "○○ 행복주택"처럼 이름에 공급 유형이 있으면 괄호에 다시 쓰지 않는다.
        SeoLabels.knownRentalType(complex.rentalType()).filter(label -> !complex.name().contains(label))
                .ifPresent(context::add);
        if (context.isEmpty()) {
            return complex.name() + " | " + SeoHtmlDocument.SITE_NAME;
        }
        return complex.name() + " (" + String.join(" ", context) + ") | " + SeoHtmlDocument.SITE_NAME;
    }

    private SeoBreadcrumb breadcrumb(String path) {
        SeoBreadcrumb breadcrumb = new SeoBreadcrumb();
        districtCode.ifPresent(code -> breadcrumb.add(regionName().orElse(code), "/regions/" + code));
        return breadcrumb.add(complex.name(), path);
    }

    private String body(SeoBreadcrumb breadcrumb) {
        SeoHtml html = new SeoHtml().open("main");
        breadcrumb.appendTo(html);
        html.element("h1", complex.name()).open("dl")
                .fact("지역", regionName().orElse(null))
                .fact("주소", address().map(HousingComplexAddressResponse::roadAddress).orElse(null))
                .fact("공급 유형", SeoLabels.knownRentalType(complex.rentalType()).orElse(null))
                .fact("공급 기관", agencyName())
                .fact("준공", Optional.ofNullable(complex.completionDate()).map(SeoHtml::date).orElse(null))
                .fact("총 세대수", Optional.ofNullable(complex.totalHouseholdCount()).map(count -> count + "세대")
                        .orElse(null))
                .close("dl");
        housingTypes(html);
        announcements(html);
        html.open("p").link("/explore", "지도에서 주변 단지 둘러보기").close("p");
        return html.close("main").build();
    }

    private void housingTypes(SeoHtml html) {
        List<String> names = complex.housingTypes().stream().map(HousingTypeDetailResponse::name)
                .filter(name -> name != null && !name.isBlank()).toList();
        if (names.isEmpty()) {
            return;
        }
        html.element("h2", "주택형").open("ul");
        names.forEach(name -> html.element("li", name));
        html.close("ul");
    }

    private void announcements(SeoHtml html) {
        html.element("h2", "모집공고");
        if (complex.currentAnnouncements().isEmpty()) {
            html.element("p", "지금 모집 중인 공고가 없어요. 알림을 받으면 새 공고가 올라올 때 알려 드려요.");
            return;
        }
        html.open("ul");
        complex.currentAnnouncements().forEach(announcement -> html.open("li")
                .link("/announcements/" + announcement.announcementId(), announcement.title())
                .text(announcementSummary(announcement)).close("li"));
        html.close("ul");
    }

    private static String announcementSummary(CurrentAnnouncementResponse announcement) {
        String status = SeoLabels.applicationStatus(announcement.applicationStatus()).orElse("");
        String period = SeoAnnouncementPeriod.withSuffix(
                SeoAnnouncementPeriod.text(announcement.applicationStartAt(), announcement.applicationEndAt()));
        return " " + String.join(" · ", List.of(status, period).stream().filter(text -> !text.isBlank()).toList());
    }

    private Map<String, Object> residence(String path) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("@context", "https://schema.org");
        data.put("@type", "ApartmentComplex");
        data.put("name", complex.name());
        data.put("url", SeoHtmlDocument.SITE_ORIGIN + path);
        address().ifPresent(address -> putAddress(data, address));
        return data;
    }

    private static void putAddress(Map<String, Object> data, HousingComplexAddressResponse address) {
        Map<String, Object> postalAddress = new LinkedHashMap<>();
        postalAddress.put("@type", "PostalAddress");
        postalAddress.put("addressCountry", "KR");
        Optional.ofNullable(address.regionName()).ifPresent(name -> postalAddress.put("addressRegion", name));
        Optional.ofNullable(address.roadAddress()).ifPresent(road -> postalAddress.put("streetAddress", road));
        data.put("address", postalAddress);
        if (address.latitude() == null || address.longitude() == null) {
            return;
        }
        data.put("geo", Map.of("@type", "GeoCoordinates",
                "latitude", address.latitude(), "longitude", address.longitude()));
    }

    private Optional<HousingComplexAddressResponse> address() {
        return Optional.ofNullable(complex.address());
    }

    private Optional<String> regionName() {
        return address().map(HousingComplexAddressResponse::regionName).filter(name -> !name.isBlank());
    }

    private String agencyName() {
        if (complex.agency() == null) {
            return null;
        }
        return SeoLabels.knownAgency(complex.agency().name());
    }
}
