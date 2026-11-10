package com.toadzip.backend.seo.service;

import com.toadzip.backend.housing.dto.response.HousingComplexListItemResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListResponse;
import java.util.List;

/** 지역·공급 유형 목록 페이지. 단지 상세와 하위 지역·유형 페이지로 내부 링크를 둔다. */
final class RegionSeoPage {

    record Child(SeoRegionPage page, String name, long complexCount) {
    }

    private final SeoRegionPage page;
    private final String regionName;
    private final String provinceName;
    private final HousingComplexListResponse complexes;
    private final List<Child> children;
    private final boolean indexable;

    RegionSeoPage(SeoRegionPage page, String regionName, String provinceName,
            HousingComplexListResponse complexes, List<Child> children, boolean indexable) {
        this.page = page;
        this.regionName = regionName;
        this.provinceName = provinceName;
        this.complexes = complexes;
        this.children = List.copyOf(children);
        this.indexable = indexable;
    }

    SeoHtmlDocument document() {
        String subject = regionName + " " + typeName();
        SeoBreadcrumb breadcrumb = breadcrumb(subject);
        return new SeoHtmlDocument(
                subject + " 단지 목록 | " + SeoHtmlDocument.SITE_NAME,
                regionName + "의 " + typeName() + " 단지를 복덕방 지도에서 확인하고, 새 모집공고 알림을 받아 보세요.",
                page.path(),
                indexable,
                List.of(breadcrumb.structuredData()),
                body(subject, breadcrumb));
    }

    private String typeName() {
        return SeoLabels.rentalType(page.rentalType()).orElse("공공임대주택");
    }

    private SeoBreadcrumb breadcrumb(String subject) {
        SeoBreadcrumb breadcrumb = new SeoBreadcrumb();
        String province = page.regionCode().substring(0, 2);
        if (page.regionCode().length() == 5) {
            breadcrumb.add(provinceName, new SeoRegionPage(province, null).path());
        }
        if (page.rentalType() != null) {
            breadcrumb.add(regionName, new SeoRegionPage(page.regionCode(), null).path());
        }
        return breadcrumb.add(subject, page.path());
    }

    private String body(String subject, SeoBreadcrumb breadcrumb) {
        SeoHtml html = new SeoHtml().open("main");
        breadcrumb.appendTo(html);
        html.element("h1", subject + " 단지").element("p", "공개된 단지 " + complexes.totalCount() + "곳");
        html.open("ul");
        complexes.items().forEach(complex -> html.open("li")
                .link("/complexes/" + complex.complexId(), complex.name()).text(complexSummary(complex)).close("li"));
        html.close("ul");
        if (complexes.hasNext()) {
            html.element("p", "나머지 단지는 지도에서 이어서 볼 수 있어요.");
        }
        childLinks(html);
        html.open("p").link(page.path(), "지도에서 " + subject + " 보기").close("p");
        return html.close("main").build();
    }

    private void childLinks(SeoHtml html) {
        if (children.isEmpty()) {
            return;
        }
        html.element("h2", childHeading()).open("ul");
        children.forEach(child -> html.listLink(child.page().path(), child.name() + " (" + child.complexCount() + "곳)"));
        html.close("ul");
    }

    private String childHeading() {
        if (page.regionCode().length() == 2) {
            return "시·군·구별 단지";
        }
        return "공급 유형별 단지";
    }

    private static String complexSummary(HousingComplexListItemResponse complex) {
        String type = SeoLabels.rentalType(complex.rentalType()).orElse("");
        if (type.isEmpty()) {
            return "";
        }
        return " · " + type;
    }
}
