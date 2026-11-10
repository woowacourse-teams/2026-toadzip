package com.toadzip.backend.seo.controller;

import com.toadzip.backend.seo.dto.response.SeoPage;
import com.toadzip.backend.seo.service.SeoPageService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프론트 Nginx가 /complexes/{id}·/announcements/{id}·/regions/… 요청을 이 주소로 넘긴다.
 * 응답 HTML의 SSI 지시문은 프론트 Nginx가 처리한다.
 */
@RestController
@RequestMapping("/api/v1/seo/pages")
public class SeoPageController {

    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final SeoPageService pageService;

    public SeoPageController(SeoPageService pageService) {
        this.pageService = pageService;
    }

    @GetMapping("/complexes/{complexId}")
    public ResponseEntity<String> complex(@PathVariable long complexId) {
        return response(pageService.complexPage(complexId));
    }

    @GetMapping("/announcements/{announcementId}")
    public ResponseEntity<String> announcement(@PathVariable long announcementId) {
        return response(pageService.announcementPage(announcementId));
    }

    @GetMapping("/regions/{regionCode}")
    public ResponseEntity<String> region(@PathVariable String regionCode) {
        return response(pageService.regionPage(regionCode, null));
    }

    @GetMapping("/regions/{regionCode}/{rentalType}")
    public ResponseEntity<String> regionRentalType(@PathVariable String regionCode, @PathVariable String rentalType) {
        return response(pageService.regionPage(regionCode, rentalType));
    }

    private static ResponseEntity<String> response(SeoPage page) {
        if (page.redirectPath() != null) {
            // 단지 합치기를 되돌리면 예전 ID가 다시 쓰일 수 있어 브라우저가 이동을 오래 기억하지 않게 한다.
            return ResponseEntity.status(page.status()).location(URI.create(page.redirectPath()))
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1))).build();
        }
        return ResponseEntity.status(page.status())
                .cacheControl(CacheControl.noStore())
                .contentType(HTML)
                .body(page.html());
    }
}
