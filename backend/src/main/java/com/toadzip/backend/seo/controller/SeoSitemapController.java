package com.toadzip.backend.seo.controller;

import com.toadzip.backend.seo.service.SeoSitemapService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 Nginx가 /sitemaps/{name}.xml 요청을 이 주소로 넘긴다. */
@RestController
@RequestMapping("/api/v1/seo/sitemaps")
public class SeoSitemapController {

    private static final MediaType XML = new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8);

    private final SeoSitemapService sitemapService;

    public SeoSitemapController(SeoSitemapService sitemapService) {
        this.sitemapService = sitemapService;
    }

    @GetMapping("/{name}.xml")
    public ResponseEntity<String> sitemap(@PathVariable String name) {
        return sitemapService.sitemap(name)
                .map(xml -> ResponseEntity.ok().contentType(XML).cacheControl(CacheControl.noCache()).body(xml))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
