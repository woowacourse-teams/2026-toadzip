package com.toadzip.backend.seo.service;

import com.toadzip.backend.housing.service.HousingComplexCodeMapper;
import com.toadzip.backend.seo.repository.SeoPageQueryRepository;
import com.toadzip.backend.seo.repository.SeoSitemapEntry;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 단지·공고·지역 페이지 사이트맵. 공개 경로 사이트맵과 사이트맵 인덱스는 프론트 빌드가 만든다. */
@Service
public class SeoSitemapService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SeoPageQueryRepository pageQueryRepository;
    private final HousingComplexCodeMapper codeMapper;

    public SeoSitemapService(SeoPageQueryRepository pageQueryRepository, HousingComplexCodeMapper codeMapper) {
        this.pageQueryRepository = pageQueryRepository;
        this.codeMapper = codeMapper;
    }

    /** 이름이 complexes·announcements·regions가 아니면 비어 있다. */
    public Optional<String> sitemap(String name) {
        return switch (name) {
            case "complexes" -> Optional.of(entries("/complexes/", pageQueryRepository.findPublicComplexes()));
            case "announcements" -> Optional.of(entries("/announcements/",
                    pageQueryRepository.findListedAnnouncements()));
            case "regions" -> Optional.of(regions());
            default -> Optional.empty();
        };
    }

    private String entries(String prefix, List<SeoSitemapEntry> entries) {
        StringBuilder xml = urlset();
        entries.forEach(entry -> url(xml, prefix + entry.id(), entry.lastModified()));
        return xml.append("</urlset>\n").toString();
    }

    private String regions() {
        StringBuilder xml = urlset();
        new SeoRegionCounts(pageQueryRepository.findRegionCounts(), codeMapper).indexablePages()
                .forEach(page -> url(xml, page.path(), null));
        return xml.append("</urlset>\n").toString();
    }

    private static StringBuilder urlset() {
        return new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    }

    private static void url(StringBuilder xml, String path, OffsetDateTime lastModified) {
        xml.append("  <url><loc>").append(SeoHtml.escape(SeoHtmlDocument.SITE_ORIGIN + path)).append("</loc>");
        if (lastModified != null) {
            xml.append("<lastmod>").append(lastModified.atZoneSameInstant(SEOUL).toLocalDate()).append("</lastmod>");
        }
        xml.append("</url>\n");
    }
}
