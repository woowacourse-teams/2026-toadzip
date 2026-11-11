package com.toadzip.backend.seo.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 홈 → 지도 → … 이동 경로. 본문 nav와 BreadcrumbList 구조화 데이터를 같은 목록으로 만든다. */
final class SeoBreadcrumb {

    private record Item(String name, String path) {
    }

    private final List<Item> items = new ArrayList<>();

    SeoBreadcrumb() {
        add("홈", "/");
        add("공공임대주택 지도", "/explore");
    }

    SeoBreadcrumb add(String name, String path) {
        items.add(new Item(name, path));
        return this;
    }

    void appendTo(SeoHtml html) {
        html.open("nav aria-label=\"이동 경로\"").open("ol");
        items.forEach(item -> html.listLink(item.path(), item.name()));
        html.close("ol").close("nav");
    }

    Map<String, Object> structuredData() {
        List<Map<String, Object>> elements = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("@type", "ListItem");
            element.put("position", index + 1);
            element.put("name", item.name());
            element.put("item", SeoHtmlDocument.SITE_ORIGIN + item.path());
            elements.add(element);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("@context", "https://schema.org");
        data.put("@type", "BreadcrumbList");
        data.put("itemListElement", elements);
        return data;
    }
}
