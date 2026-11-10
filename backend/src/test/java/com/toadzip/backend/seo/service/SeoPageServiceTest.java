package com.toadzip.backend.seo.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.SupplyComplexResponse;
import com.toadzip.backend.announcement.dto.response.SupplyRowResponse;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.service.AnnouncementQueryService;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.response.AgencyResponse;
import com.toadzip.backend.housing.dto.response.CurrentAnnouncementResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexAddressResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexDetailResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListItemResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListResponse;
import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.service.HousingComplexCodeMapper;
import com.toadzip.backend.housing.service.HousingComplexQueryService;
import com.toadzip.backend.region.repository.RegionSearchResult;
import com.toadzip.backend.seo.dto.response.SeoPage;
import com.toadzip.backend.seo.repository.SeoPageQueryRepository;
import com.toadzip.backend.seo.repository.SeoRegionCount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SeoPageServiceTest {

    private HousingComplexQueryService complexQueryService;
    private AnnouncementQueryService announcementQueryService;
    private SeoPageQueryRepository pageQueryRepository;
    private SeoPageService service;

    @BeforeEach
    void setUp() {
        complexQueryService = mock(HousingComplexQueryService.class);
        announcementQueryService = mock(AnnouncementQueryService.class);
        pageQueryRepository = mock(SeoPageQueryRepository.class);
        SeoRegionCatalog catalog = new SeoRegionCatalog(keyword -> List.of(
                new RegionSearchResult("11", "서울특별시", null, "서울특별시"),
                new RegionSearchResult("11140", "서울특별시", "중구", "서울특별시 중구"),
                new RegionSearchResult("28", "인천광역시", null, "인천광역시"),
                new RegionSearchResult("28200", "인천광역시", "남동구", "인천광역시 남동구")));
        service = new SeoPageService(complexQueryService, announcementQueryService, pageQueryRepository, catalog,
                new HousingComplexCodeMapper(), JsonMapper.builder().build());
    }

    @Test
    void 단지_페이지는_지역과_공급유형을_담은_제목과_숫자_없는_설명과_대표주소를_가진다() {
        when(complexQueryService.getComplex(17)).thenReturn(complex(17, "서울가람"));

        SeoPage page = service.complexPage(17);

        String html = page.html();
        assertAll(
                () -> assertEquals(200, page.status()),
                () -> assertTrue(html.contains("<title>서울가람 (서울 중구 행복주택) | 공공주택 복덕방</title>")),
                () -> assertTrue(html.contains("<meta name=\"description\" content=\"서울가람의 주택형과 모집공고를 복덕방 "
                        + "지도에서 확인하고, 새 공고 알림을 받아 보세요.\">")),
                () -> assertTrue(html.contains("<link rel=\"canonical\" href=\"https://bokduckbang.com/complexes/17\">")),
                () -> assertTrue(html.contains(SeoHtmlDocument.SHARED_HEAD)),
                () -> assertTrue(html.contains("<h1>서울가람</h1>")),
                () -> assertTrue(html.contains("<a href=\"/regions/11140\">서울특별시 중구</a>")),
                () -> assertTrue(html.contains("<a href=\"/announcements/171\">")),
                () -> assertTrue(html.contains("\"@type\":\"ApartmentComplex\"")),
                () -> assertFalse(html.contains("noindex"))
        );
    }

    @Test
    void 단지_이름의_태그와_SSI_지시문은_이스케이프한다() {
        when(complexQueryService.getComplex(17)).thenReturn(complex(17, "<!--# include virtual=\"/x\" --></script>"));

        String html = service.complexPage(17).html();

        assertAll(
                () -> assertEquals(1, html.split("<!--#", -1).length - 1),
                () -> assertFalse(html.contains("</script></script>")),
                () -> assertTrue(html.contains("\\u003c!--# include"))
        );
    }

    @Test
    void 합쳐진_단지의_예전_ID는_현재_단지_주소로_보내고_없는_단지는_404다() {
        when(complexQueryService.getComplex(5)).thenReturn(complex(17, "서울가람"));
        when(complexQueryService.getComplex(9)).thenThrow(new HousingComplexNotFoundException());

        SeoPage redirect = service.complexPage(5);
        SeoPage missing = service.complexPage(9);

        assertAll(
                () -> assertEquals(308, redirect.status()),
                () -> assertEquals("/complexes/17", redirect.redirectPath()),
                () -> assertEquals(404, missing.status()),
                () -> assertTrue(missing.html().contains("noindex")),
                () -> assertFalse(missing.html().contains("rel=\"canonical\""))
        );
    }

    @Test
    void 공고_페이지_제목은_지역_유형_모집_접수기간을_담고_공급단지로_연결한다() {
        when(announcementQueryService.getAnnouncement(171)).thenReturn(announcement(ApplicationStatus.BEFORE_APPLICATION));
        when(pageQueryRepository.isListedAnnouncement(171)).thenReturn(true);

        String html = service.announcementPage(171).html();

        assertAll(
                () -> assertTrue(html.contains(
                        "<title>인천 남동구 국민임대 예비입주자 모집 (11.16~11.17 접수) | 공공주택 복덕방</title>")),
                () -> assertTrue(html.contains("<h1>[정정공고] 남동구 국민임대 예비입주자 모집</h1>")),
                () -> assertTrue(html.contains("<a href=\"/complexes/17\">서울가람</a>")),
                () -> assertTrue(html.contains("<a href=\"https://apply.lh.or.kr/171\">공고 원문 보기</a>")),
                () -> assertFalse(html.contains("noindex"))
        );
    }

    @Test
    void 마감_공고는_제목에_마감을_표시하고_목록에서_빠진_공고는_색인하지_않는다() {
        when(announcementQueryService.getAnnouncement(171)).thenReturn(announcement(ApplicationStatus.CLOSED));
        when(pageQueryRepository.isListedAnnouncement(171)).thenReturn(false);
        when(announcementQueryService.getAnnouncement(9)).thenThrow(new AnnouncementNotFoundException());

        String html = service.announcementPage(171).html();

        assertAll(
                () -> assertTrue(html.contains("<title>인천 남동구 국민임대 예비입주자 모집 (접수 마감) | 공공주택 복덕방</title>")),
                () -> assertTrue(html.contains("<meta name=\"robots\" content=\"noindex, follow\">")),
                () -> assertEquals(404, service.announcementPage(9).status())
        );
    }

    @Test
    void 지역_페이지는_단지와_하위_페이지로_연결하고_기준보다_적으면_색인하지_않는다() {
        when(pageQueryRepository.findRegionCounts()).thenReturn(List.of(
                new SeoRegionCount("11", "11140", "행복주택", 2),
                new SeoRegionCount("11", "11140", "국민임대", 1)));
        when(complexQueryService.getComplexes(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), eq(null), anyInt()))
                .thenReturn(new HousingComplexListResponse(List.of(listItem(17, "서울가람")), null, false, 3));

        String district = service.regionPage("11140", null).html();
        String type = service.regionPage("11140", "national-rental").html();

        assertAll(
                () -> assertTrue(district.contains("<title>서울특별시 중구 공공임대주택 단지 목록 | 공공주택 복덕방</title>")),
                () -> assertTrue(district.contains("<a href=\"/complexes/17\">서울가람</a>")),
                () -> assertTrue(district.contains("<a href=\"/regions/11140/happy-housing\">행복주택 (2곳)</a>")),
                () -> assertTrue(district.contains("<a href=\"/regions/11\">서울특별시</a>")),
                () -> assertFalse(district.contains("noindex")),
                () -> assertTrue(type.contains("<link rel=\"canonical\" href=\"https://bokduckbang.com/regions/11140/"
                        + "national-rental\">"))
        );
    }

    @Test
    void 기타_유형_지역_페이지는_단지가_많아도_색인하지_않는다() {
        when(pageQueryRepository.findRegionCounts()).thenReturn(List.of(new SeoRegionCount("11", "11140", "기타", 5)));
        when(complexQueryService.getComplexes(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), eq(null), anyInt()))
                .thenReturn(new HousingComplexListResponse(List.of(listItem(17, "서울가람")), null, false, 5));

        assertTrue(service.regionPage("11140", "etc").html().contains("<meta name=\"robots\" content=\"noindex, follow\">"));
    }

    @Test
    void 모르는_지역과_공급유형과_단지가_없는_지역은_404다() {
        when(pageQueryRepository.findRegionCounts()).thenReturn(List.of(new SeoRegionCount("11", "11140", "행복주택", 1)));

        assertAll(
                () -> assertEquals(404, service.regionPage("99999", null).status()),
                () -> assertEquals(404, service.regionPage("11140", "unknown").status()),
                () -> assertEquals(404, service.regionPage("28200", null).status()),
                () -> assertEquals(404, service.regionPage("11140", "national-rental").status())
        );
    }

    private static HousingComplexDetailResponse complex(long complexId, String name) {
        return new HousingComplexDetailResponse(complexId, name, "HAPPY_HOUSING", new AgencyResponse("LH", "한국토지주택공사"),
                new HousingComplexAddressResponse("서울특별시 중구", "서울특별시 중구 세종대로 110",
                        new BigDecimal("37.5"), new BigDecimal("126.9")),
                LocalDate.of(2020, 1, 1), "아파트", true, "개별난방", "계단식", 3, 100, 80,
                null, null, null, null, List.of(), null, List.of(),
                List.of(new CurrentAnnouncementResponse(171, "남동구 국민임대", "ORIGINAL", "APPLYING", List.of(),
                        LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 17), 6, null)));
    }

    private static HousingComplexListItemResponse listItem(long complexId, String name) {
        return new HousingComplexListItemResponse(complexId, null, "서울특별시 중구", name, "HAPPY_HOUSING", null,
                null, null, null, null, null, null, null);
    }

    private static AnnouncementDetailResponse announcement(ApplicationStatus status) {
        SupplyRowResponse row = new SupplyRowResponse(1, "서울가람", "36A",
                new SupplyComplexResponse(17, "서울가람", "주소", 100, null), null, null, null, 10, List.of());
        return new AnnouncementDetailResponse(171, AnnouncementPublicationType.CORRECTION, null, status,
                RentalType.NATIONAL_RENTAL, RecruitmentType.WAITLIST, "[정정공고] 남동구 국민임대 예비입주자 모집",
                List.of("인천광역시 남동구"),
                new com.toadzip.backend.announcement.dto.response.AgencyResponse(AgencyCode.LH, "한국토지주택공사"),
                LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 17), 6,
                LocalDate.of(2027, 1, 20), 0, List.of(), 1, 60, "https://apply.lh.or.kr/171", List.of(), List.of(),
                List.of(), List.of(row), null, List.of(), null);
    }
}
