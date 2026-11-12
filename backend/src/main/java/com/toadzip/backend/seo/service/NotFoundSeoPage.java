package com.toadzip.backend.seo.service;

import java.util.List;

/** 없는 단지·공고·지역. 404와 함께 내보내고 색인하지 않는다. */
final class NotFoundSeoPage {

    private NotFoundSeoPage() {
    }

    static SeoHtmlDocument document() {
        String body = new SeoHtml().open("main")
                .element("h1", "페이지를 찾을 수 없어요.")
                .element("p", "입력한 주소를 다시 확인해 주세요.")
                .open("p").link("/explore", "지도로 돌아가기").close("p")
                .close("main").build();
        return new SeoHtmlDocument("페이지를 찾을 수 없어요 | " + SeoHtmlDocument.SITE_NAME,
                "찾는 단지·공고가 없거나 주소가 바뀌었어요.", null, false, List.of(), body);
    }
}
