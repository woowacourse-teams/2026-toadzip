package com.toadzip.backend.seo.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.seo.dto.response.SeoPage;
import com.toadzip.backend.seo.service.SeoPageService;
import com.toadzip.backend.seo.service.SeoSitemapService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({SeoPageController.class, SeoSitemapController.class})
@AutoConfigureMockMvc(addFilters = false)
class SeoPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SeoPageService pageService;

    @MockitoBean
    private SeoSitemapService sitemapService;

    @Test
    void 페이지는_JSON을_요청해도_UTF8_HTML로_상태코드와_함께_응답한다() throws Exception {
        when(pageService.complexPage(17)).thenReturn(SeoPage.found("<h1>단지</h1>"));
        when(pageService.announcementPage(9)).thenReturn(SeoPage.notFound("<h1>없음</h1>"));

        mockMvc.perform(get("/api/v1/seo/pages/complexes/17").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string("<h1>단지</h1>"));
        mockMvc.perform(get("/api/v1/seo/pages/announcements/9"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("<h1>없음</h1>"));
    }

    @Test
    void 이동할_페이지는_308과_상대_주소로_응답한다() throws Exception {
        when(pageService.complexPage(5)).thenReturn(SeoPage.redirect("/complexes/17"));

        mockMvc.perform(get("/api/v1/seo/pages/complexes/5"))
                .andExpect(status().isPermanentRedirect())
                .andExpect(header().string("Location", "/complexes/17"));
    }

    @Test
    void 지역_페이지는_공급유형_주소까지_서비스로_넘긴다() throws Exception {
        when(pageService.regionPage("11140", null)).thenReturn(SeoPage.found("district"));
        when(pageService.regionPage("11140", "happy-housing")).thenReturn(SeoPage.found("type"));

        mockMvc.perform(get("/api/v1/seo/pages/regions/11140")).andExpect(content().string("district"));
        mockMvc.perform(get("/api/v1/seo/pages/regions/11140/happy-housing")).andExpect(content().string("type"));
    }

    @Test
    void 사이트맵은_XML로_응답하고_모르는_이름은_404다() throws Exception {
        when(sitemapService.sitemap("complexes")).thenReturn(Optional.of("<urlset/>"));
        when(sitemapService.sitemap("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/seo/sitemaps/complexes.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/xml;charset=UTF-8"))
                .andExpect(content().string("<urlset/>"));
        mockMvc.perform(get("/api/v1/seo/sitemaps/unknown.xml")).andExpect(status().isNotFound());
    }
}
