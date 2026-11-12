package com.toadzip.backend.seo.service;

import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.service.AnnouncementQueryService;
import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingComplexDetailResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListResponse;
import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.service.HousingComplexCodeMapper;
import com.toadzip.backend.housing.service.HousingComplexQueryService;
import com.toadzip.backend.seo.dto.response.SeoPage;
import com.toadzip.backend.seo.repository.SeoPageQueryRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 검색엔진·링크 미리보기가 읽는 단지·공고·지역 페이지. 데이터는 공개 API와 같은 조회를 쓴다. */
@Service
public class SeoPageService {

    private static final int REGION_PAGE_SIZE = 50;

    private final HousingComplexQueryService complexQueryService;
    private final AnnouncementQueryService announcementQueryService;
    private final SeoPageQueryRepository pageQueryRepository;
    private final SeoRegionCatalog regionCatalog;
    private final HousingComplexCodeMapper codeMapper;
    private final ObjectMapper objectMapper;

    public SeoPageService(
            HousingComplexQueryService complexQueryService,
            AnnouncementQueryService announcementQueryService,
            SeoPageQueryRepository pageQueryRepository,
            SeoRegionCatalog regionCatalog,
            HousingComplexCodeMapper codeMapper,
            ObjectMapper objectMapper
    ) {
        this.complexQueryService = complexQueryService;
        this.announcementQueryService = announcementQueryService;
        this.pageQueryRepository = pageQueryRepository;
        this.regionCatalog = regionCatalog;
        this.codeMapper = codeMapper;
        this.objectMapper = objectMapper;
    }

    public SeoPage complexPage(long complexId) {
        Optional<HousingComplexDetailResponse> complex = findComplex(complexId);
        if (complex.isEmpty()) {
            return notFound();
        }
        HousingComplexDetailResponse found = complex.get();
        // 합쳐진 단지의 예전 ID는 현재 단지 주소로 보낸다.
        if (found.complexId() != complexId) {
            return SeoPage.redirect("/complexes/" + found.complexId());
        }
        Optional<String> districtCode = regionCatalog.districtCode(regionName(found));
        return SeoPage.found(new ComplexSeoPage(found, districtCode).document().render(objectMapper));
    }

    public SeoPage announcementPage(long announcementId) {
        Optional<AnnouncementDetailResponse> announcement = findAnnouncement(announcementId);
        if (announcement.isEmpty()) {
            return notFound();
        }
        boolean listed = pageQueryRepository.isListedAnnouncement(announcementId);
        return SeoPage.found(new AnnouncementSeoPage(announcement.get(), listed).document().render(objectMapper));
    }

    public SeoPage regionPage(String regionCode, String rentalTypeSlug) {
        Optional<String> regionName = regionCatalog.name(regionCode);
        Optional<String> rentalType = Optional.ofNullable(rentalTypeSlug).flatMap(SeoLabels::rentalTypeFromSlug);
        if (regionName.isEmpty() || (rentalTypeSlug != null && rentalType.isEmpty())) {
            return notFound();
        }
        SeoRegionPage page = new SeoRegionPage(regionCode, rentalType.orElse(null));
        SeoRegionCounts counts = new SeoRegionCounts(pageQueryRepository.findRegionCounts(), codeMapper);
        if (counts.count(page) == 0) {
            return notFound();
        }
        String provinceName = regionCatalog.name(regionCode.substring(0, 2)).orElse(regionName.get());
        RegionSeoPage regionPage = new RegionSeoPage(page, regionName.get(), provinceName,
                regionComplexes(page), children(page, counts), counts.indexable(page));
        return SeoPage.found(regionPage.document().render(objectMapper));
    }

    private List<RegionSeoPage.Child> children(SeoRegionPage page, SeoRegionCounts counts) {
        return counts.children(page).stream()
                .map(child -> new RegionSeoPage.Child(child, childName(child), counts.count(child)))
                .toList();
    }

    private String childName(SeoRegionPage child) {
        if (child.rentalType() != null) {
            return SeoLabels.rentalType(child.rentalType()).orElse(child.rentalType());
        }
        return regionCatalog.name(child.regionCode()).orElse(child.regionCode());
    }

    private HousingComplexListResponse regionComplexes(SeoRegionPage page) {
        List<RentalType> rentalTypes = Optional.ofNullable(page.rentalType()).map(RentalType::fromCode)
                .map(List::of).orElse(List.of());
        HousingComplexSearchRequest request = new HousingComplexSearchRequest(null, page.regionCode(), rentalTypes,
                List.of(), List.of(), List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        return complexQueryService.getComplexes(request, ComplexSort.LATEST_ANNOUNCEMENT, null, REGION_PAGE_SIZE);
    }

    private Optional<HousingComplexDetailResponse> findComplex(long complexId) {
        try {
            return Optional.of(complexQueryService.getComplex(complexId));
        } catch (HousingComplexNotFoundException exception) {
            return Optional.empty();
        }
    }

    private Optional<AnnouncementDetailResponse> findAnnouncement(long announcementId) {
        try {
            return Optional.of(announcementQueryService.getAnnouncement(announcementId));
        } catch (AnnouncementNotFoundException exception) {
            return Optional.empty();
        }
    }

    private static String regionName(HousingComplexDetailResponse complex) {
        if (complex.address() == null) {
            return null;
        }
        return complex.address().regionName();
    }

    private SeoPage notFound() {
        return SeoPage.notFound(NotFoundSeoPage.document().render(objectMapper));
    }
}
