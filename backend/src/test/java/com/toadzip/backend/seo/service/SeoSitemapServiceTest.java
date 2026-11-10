package com.toadzip.backend.seo.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.service.HousingComplexCodeMapper;
import com.toadzip.backend.seo.repository.SeoPageQueryRepository;
import com.toadzip.backend.seo.repository.SeoRegionCount;
import com.toadzip.backend.seo.repository.SeoSitemapEntry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SeoSitemapServiceTest {

    private final SeoPageQueryRepository repository = mock(SeoPageQueryRepository.class);
    private final SeoSitemapService service = new SeoSitemapService(repository, new HousingComplexCodeMapper());

    @Test
    void 단지_사이트맵은_서울_날짜로_수정일을_쓴다() {
        when(repository.findPublicComplexes()).thenReturn(List.of(
                new SeoSitemapEntry(17, OffsetDateTime.parse("2026-11-09T16:30:00Z")),
                new SeoSitemapEntry(18, null)));

        String xml = service.sitemap("complexes").orElseThrow();

        assertAll(
                () -> assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")),
                () -> assertTrue(xml.contains(
                        "<url><loc>https://bokduckbang.com/complexes/17</loc><lastmod>2026-11-10</lastmod></url>")),
                () -> assertTrue(xml.contains("<url><loc>https://bokduckbang.com/complexes/18</loc></url>"))
        );
    }

    @Test
    void 지역_사이트맵은_기준_이상인_지역과_공급유형만_넣고_기타는_뺀다() {
        when(repository.findRegionCounts()).thenReturn(List.of(
                new SeoRegionCount("11", "11140", "행복주택", 3),
                new SeoRegionCount("11", "11140", "기타", 5),
                new SeoRegionCount("11", "11170", "국민임대", 1)));

        String xml = service.sitemap("regions").orElseThrow();

        assertAll(
                () -> assertTrue(xml.contains("/regions/11</loc>")),
                () -> assertTrue(xml.contains("/regions/11140</loc>")),
                () -> assertTrue(xml.contains("/regions/11140/happy-housing</loc>")),
                () -> assertTrue(xml.contains("/regions/11/happy-housing</loc>")),
                () -> assertFalse(xml.contains("/regions/11140/etc")),
                () -> assertFalse(xml.contains("/regions/11170")),
                () -> assertEquals(Optional.empty(), service.sitemap("unknown"))
        );
    }
}
