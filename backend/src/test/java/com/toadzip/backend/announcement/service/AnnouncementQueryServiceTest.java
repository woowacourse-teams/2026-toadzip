package com.toadzip.backend.announcement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementSort;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.domain.SupplyType;
import com.toadzip.backend.announcement.dto.request.AnnouncementSearchRequest;
import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListItemResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListResponse;
import com.toadzip.backend.announcement.dto.response.SupplyRowResponse;
import com.toadzip.backend.announcement.dto.response.SupplyTargetResponse;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.exception.InvalidRegionCodeException;
import com.toadzip.backend.announcement.repository.AnnouncementApplicationScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementDeadlineRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementSearchCondition;
import com.toadzip.backend.announcement.repository.AnnouncementSearchRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AnnouncementQueryServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Mock
    private AnnouncementRepository announcementRepository;

    @Mock
    private AnnouncementSearchRepository announcementSearchRepository;

    @Mock
    private AnnouncementApplicationScheduleRepository applicationScheduleRepository;

    @Mock
    private AnnouncementScheduleRepository announcementScheduleRepository;

    @Mock
    private AnnouncementAttachmentRepository announcementAttachmentRepository;

    @Mock
    private SupplyRowRepository supplyRowRepository;

    @Mock
    private SupplyTargetRepository supplyTargetRepository;

    @Mock
    private RegionCodeResolver regionCodeResolver;

    private final AnnouncementCursorCodec announcementCursorCodec = new AnnouncementCursorCodec();
    private AnnouncementQueryService announcementQueryService;
    private long nextId;

    @BeforeEach
    void setUp() {
        announcementQueryService = new AnnouncementQueryService(
                announcementRepository,
                applicationScheduleRepository,
                announcementSearchRepository,
                announcementScheduleRepository,
                announcementAttachmentRepository,
                supplyRowRepository,
                supplyTargetRepository,
                new AnnouncementResponseMapper(regionCodeResolver, new AnnouncementApplicationStatusCalculator()),
                announcementCursorCodec,
                fixedClock(),
                regionCodeResolver
        );
    }

    @Test
    void 목록은_저장소의_순서를_유지하며_상태와_집계를_반환한다() {
        Announcement original = withId(createAnnouncement(
                "original",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 9),
                null,
                null,
                1L
        ));
        Announcement correction = withId(createAnnouncement(
                "correction",
                original,
                "original",
                AnnouncementPublicationType.CORRECTION,
                LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                new BigDecimal("2.5000"),
                new BigDecimal("3.7500"),
                37L
        ));
        Announcement applying = withId(createAnnouncement(
                "applying",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 4),
                LocalDate.of(2026, 8, 9),
                LocalDate.of(2026, 8, 11),
                null,
                null,
                3L
        ));
        Announcement beforeApplication = withId(createAnnouncement(
                "before",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 12),
                null,
                null,
                4L
        ));
        Announcement closed = withId(createAnnouncement(
                "closed",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 9),
                null,
                null,
                5L
        ));

        HousingComplex seoulComplex = withId(createComplex(
                "서울 단지",
                "seoul-complex",
                "11",
                "11140",
                null
        ));
        HousingComplex busanComplex = withId(createComplex(
                "부산 단지",
                "busan-complex",
                "26",
                "26110",
                "https://example.com/busan.png"
        ));
        HousingComplex unresolvedComplex = withId(createComplex(
                "미해결 단지",
                "unresolved-complex",
                "99",
                "99999",
                null
        ));
        List<SupplyRow> supplyRows = List.of(
                withId(createSupplyRow(correction, seoulComplex, null, "row-1", 0, null)),
                withId(createSupplyRow(correction, seoulComplex, null, "row-2", 1, 0)),
                withId(createSupplyRow(correction, busanComplex, null, "row-3", 2, 7)),
                withId(createSupplyRow(correction, null, null, "row-4", 3, 3)),
                withId(createSupplyRow(correction, unresolvedComplex, null, "row-5", 4, null)),
                withId(createSupplyRow(correction, unresolvedComplex, null, "row-6", 5, null)),
                withId(createSupplyRow(applying, null, null, "all-null", 0, null)),
                withId(createSupplyRow(beforeApplication, null, null, "known-zero", 0, 0))
        );
        when(announcementSearchRepository.findSortedLeaves(
                any(AnnouncementSearchCondition.class), isNull(), isNull(), isNull(), isNull(),
                eq(21), eq(AnnouncementSort.LATEST)
        )).thenReturn(sortedRows(List.of(correction, applying, beforeApplication, closed)));
        when(supplyRowRepository.findAllByAnnouncementIdIn(List.of(
                correction.getId(), applying.getId(), beforeApplication.getId(), closed.getId()
        ))).thenReturn(supplyRows);
        when(regionCodeResolver.resolve("11", "11140")).thenReturn(Optional.of("서울특별시 중구"));
        when(regionCodeResolver.resolve("26", "26110")).thenReturn(Optional.of("부산광역시 중구"));

        AnnouncementListResponse response = announcementQueryService.getAnnouncements(noFilters(), null, 20);

        assertFalse(response.hasNext());
        assertNull(response.nextCursor());
        assertEquals(
                List.of(
                        correction.getId(),
                        applying.getId(),
                        beforeApplication.getId(),
                        closed.getId()
                ),
                response.items().stream().map(AnnouncementListItemResponse::announcementId).toList()
        );
        AnnouncementListItemResponse correctionItem = response.items().getFirst();
        assertEquals(AnnouncementPublicationType.CORRECTION, correctionItem.publicationType());
        assertEquals(ApplicationStatus.APPLYING, correctionItem.applicationStatus());
        assertEquals(RentalType.HAPPY_HOUSING, correctionItem.rentalType());
        assertEquals(RecruitmentType.NEW, correctionItem.recruitmentType());
        assertEquals("correction 공고", correctionItem.title());
        assertEquals(List.of("서울특별시 중구", "부산광역시 중구"), correctionItem.regionNames());
        assertEquals(LocalDate.of(2026, 8, 5), correctionItem.publishedAt());
        assertEquals(LocalDate.of(2026, 8, 1), correctionItem.applicationStartAt());
        assertEquals(LocalDate.of(2026, 8, 10), correctionItem.applicationEndAt());
        assertEquals(0, correctionItem.dDay());
        assertEquals(37L, correctionItem.viewCount());
        assertEquals(3, correctionItem.supplyComplexCount());
        assertEquals(10, correctionItem.supplyHouseholdCount());
        assertEquals(AgencyCode.LH, correctionItem.agency().code());
        assertEquals("한국토지주택공사", correctionItem.agency().name());
        assertEquals(new BigDecimal("2.5000"), correctionItem.actualCompetitionRate());
        assertEquals(new BigDecimal("3.7500"), correctionItem.predictedCompetitionRate());
        assertEquals("https://example.com/busan.png", correctionItem.thumbnailImageUrl());

        assertEquals(ApplicationStatus.APPLYING, response.items().get(1).applicationStatus());
        assertEquals(1, response.items().get(1).dDay());
        assertNull(response.items().get(1).supplyHouseholdCount());
        assertEquals(ApplicationStatus.BEFORE_APPLICATION, response.items().get(2).applicationStatus());
        assertEquals(1, response.items().get(2).dDay());
        assertEquals(0, response.items().get(2).supplyHouseholdCount());
        assertEquals(ApplicationStatus.CLOSED, response.items().get(3).applicationStatus());
        assertNull(response.items().get(3).dDay());
        assertEquals(37L, correction.getViewCount());
    }

    @Test
    void 상세는_취소공고의_사유와_취소상태를_반환한다() {
        Announcement original = withId(createAnnouncement(
                "cancelled-detail-original",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12),
                null,
                null,
                0L
        ));
        Announcement cancellation = withId(createAnnouncement(
                "cancelled-detail",
                original,
                "cancelled-detail-original",
                AnnouncementPublicationType.CANCELLATION,
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12),
                null,
                null,
                0L,
                "사업 취소"
        ));
        when(announcementRepository.findDetailById(cancellation.getId())).thenReturn(Optional.of(cancellation));

        AnnouncementDetailResponse response = announcementQueryService.getAnnouncement(cancellation.getId());

        assertEquals(cancellation.getId(), response.announcementId());
        assertEquals(AnnouncementPublicationType.CANCELLATION, response.publicationType());
        assertEquals("사업 취소", response.correctionOrCancellationReason());
        assertEquals(ApplicationStatus.CANCELLED, response.applicationStatus());
        assertNull(response.dDay());
    }

    @Test
    void 목록은_추가_한_건으로_다음_페이지를_판단하고_마지막_반환항목을_커서로_쓴다() {
        Announcement first = withId(createAnnouncement(
                "first",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11),
                null,
                null,
                0L
        ));
        Announcement second = withId(createAnnouncement(
                "second",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11),
                null,
                null,
                0L
        ));
        when(announcementSearchRepository.findSortedLeaves(
                any(AnnouncementSearchCondition.class), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(AnnouncementSort.LATEST)
        )).thenReturn(sortedRows(List.of(second, first)));
        when(announcementSearchRepository.findSortedLeaves(
                any(AnnouncementSearchCondition.class), eq(0), eq(second.getApplicationEndDate()),
                eq(second.getPostedDate()), eq(second.getId()), eq(2), eq(AnnouncementSort.LATEST)
        )).thenReturn(sortedRows(List.of(first)));

        AnnouncementListResponse firstPage = announcementQueryService.getAnnouncements(noFilters(), null, 1);
        AnnouncementListResponse secondPage = announcementQueryService.getAnnouncements(
                noFilters(), firstPage.nextCursor(), 1
        );

        assertTrue(firstPage.hasNext());
        assertEquals(second.getId(), firstPage.items().getFirst().announcementId());
        assertEquals(
                announcementCursorCodec.encodeDeadline(LocalDate.of(2026, 8, 10), 0, second.getApplicationEndDate(),
                        second.getPostedDate(), second.getId(), AnnouncementSort.LATEST),
                firstPage.nextCursor()
        );
        assertFalse(secondPage.hasNext());
        assertNull(secondPage.nextCursor());
        assertEquals(first.getId(), secondPage.items().getFirst().announcementId());
        verify(supplyRowRepository).findAllByAnnouncementIdIn(List.of(second.getId()));
        verify(supplyRowRepository).findAllByAnnouncementIdIn(List.of(first.getId()));
    }

    @Test
    void 잘못된_목록_크기는_저장소_접근_전에_거부한다() {
        AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
        AnnouncementSearchRepository announcementSearchRepository = mock(AnnouncementSearchRepository.class);
        AnnouncementResponseMapper announcementResponseMapper = mock(AnnouncementResponseMapper.class);
        AnnouncementQueryService service = new AnnouncementQueryService(
                announcementRepository,
                mock(AnnouncementApplicationScheduleRepository.class),
                announcementSearchRepository,
                mock(AnnouncementScheduleRepository.class),
                mock(AnnouncementAttachmentRepository.class),
                mock(SupplyRowRepository.class),
                mock(SupplyTargetRepository.class),
                announcementResponseMapper,
                new AnnouncementCursorCodec(),
                fixedClock(),
                mock(RegionCodeResolver.class)
        );

        assertThrows(InvalidAnnouncementRequestException.class, () -> service.getAnnouncements(noFilters(), null, 0));
        assertThrows(InvalidAnnouncementRequestException.class, () -> service.getAnnouncements(noFilters(), null, 51));
        verifyNoInteractions(announcementRepository, announcementSearchRepository, announcementResponseMapper);
    }

    @Test
    void 검색_요청의_교차_검증은_저장소_검색_전에_수행한다() {
        AnnouncementSearchRepository announcementSearchRepository = mock(AnnouncementSearchRepository.class);
        RegionCodeResolver regionCodeResolver = mock(RegionCodeResolver.class);
        AnnouncementQueryService service = searchService(announcementSearchRepository, regionCodeResolver);

        assertThrows(InvalidAnnouncementRequestException.class, () -> service.getAnnouncements(
                requestWithPeriod(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)), null, 20
        ));
        assertThrows(InvalidAnnouncementRequestException.class, () -> service.getAnnouncements(
                requestWithPublicationTypes(List.of(AnnouncementPublicationType.CANCELLATION)), null, 20
        ));
        assertThrows(InvalidAnnouncementRequestException.class, () -> service.getAnnouncements(
                requestWithApplicationStatuses(List.of(ApplicationStatus.CANCELLED)), null, 20
        ));
        assertThrows(InvalidAnnouncementRequestException.class, () -> service.getAnnouncements(
                new AnnouncementSearchRequest("   ", null, null, null, null, null, null, null, null), null, 20
        ));
        when(regionCodeResolver.filterCodes("99999")).thenReturn(Optional.empty());
        assertThrows(InvalidRegionCodeException.class, () -> service.getAnnouncements(
                requestWithRegion("99999"), null, 20
        ));

        verifyNoInteractions(announcementSearchRepository);
    }

    @Test
    void 직접_호출의_빈_enum_목록값은_저장소_검색_전에_거부한다() {
        AnnouncementSearchRepository announcementSearchRepository = mock(AnnouncementSearchRepository.class);
        AnnouncementQueryService service = searchService(announcementSearchRepository, mock(RegionCodeResolver.class));
        List<AgencyCode> agencyCodes = new ArrayList<>();
        agencyCodes.add(AgencyCode.LH);
        agencyCodes.add(null);

        assertThrows(InvalidAnnouncementRequestException.class, () -> service.getAnnouncements(
                new AnnouncementSearchRequest(null, null, null, null, null, agencyCodes, null, null, null),
                null,
                20
        ));

        verifyNoInteractions(announcementSearchRepository);
    }

    @Test
    void 유효한_지역과_검색조건과_서울_오늘을_검색과_응답변환에_같이_전달한다() {
        Announcement first = withId(createAnnouncement(
                "first", null, null, AnnouncementPublicationType.ORIGINAL, LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11), null, null, 0L
        ));
        Announcement second = withId(createAnnouncement(
                "second", null, null, AnnouncementPublicationType.ORIGINAL, LocalDate.of(2026, 8, 4),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11), null, null, 0L
        ));
        AnnouncementSearchRepository announcementSearchRepository = mock(AnnouncementSearchRepository.class);
        RegionCodeResolver regionCodeResolver = mock(RegionCodeResolver.class);
        AnnouncementResponseMapper announcementResponseMapper = mock(AnnouncementResponseMapper.class);
        when(regionCodeResolver.filterCodes("11140")).thenReturn(Optional.of(Set.of("11140", "11000")));
        when(announcementSearchRepository.findSortedLeaves(
                org.mockito.ArgumentMatchers.any(AnnouncementSearchCondition.class),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(2), eq(AnnouncementSort.LATEST)
        )).thenReturn(sortedRows(List.of(first, second)));
        when(announcementResponseMapper.toListItemResponses(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(LocalDate.class)
        )).thenReturn(List.of());
        AnnouncementQueryService service = searchService(
                announcementSearchRepository, regionCodeResolver, announcementResponseMapper
        );

        AnnouncementListResponse response = service.getAnnouncements(
                new AnnouncementSearchRequest(
                        "  행복  ", "11140", List.of(RentalType.HAPPY_HOUSING),
                        List.of(ApplicationStatus.APPLYING), List.of(AnnouncementPublicationType.ORIGINAL),
                        List.of(AgencyCode.LH), List.of(RecruitmentType.NEW),
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)
                ), null, 1
        );

        ArgumentCaptor<AnnouncementSearchCondition> conditionCaptor = ArgumentCaptor.forClass(
                AnnouncementSearchCondition.class
        );
        ArgumentCaptor<LocalDate> mapperTodayCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(announcementSearchRepository).findSortedLeaves(
                conditionCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(2), eq(AnnouncementSort.LATEST)
        );
        verify(announcementResponseMapper).toListItemResponses(
                org.mockito.ArgumentMatchers.eq(List.of(first)),
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(List.of()),
                mapperTodayCaptor.capture()
        );
        AnnouncementSearchCondition condition = conditionCaptor.getValue();
        assertEquals("행복", condition.keyword());
        assertEquals(Set.of("11140", "11000"), condition.regionCodes());
        assertEquals(Set.of(RentalType.HAPPY_HOUSING), condition.rentalTypes());
        assertEquals(LocalDate.of(2026, 8, 10), condition.today());
        assertEquals(condition.today(), mapperTodayCaptor.getValue());
        assertTrue(response.hasNext());
        assertEquals(announcementCursorCodec.encodeDeadline(LocalDate.of(2026, 8, 10), 0, first.getApplicationEndDate(),
                first.getPostedDate(), first.getId(), AnnouncementSort.LATEST), response.nextCursor());
    }

    private List<AnnouncementDeadlineRow> sortedRows(List<Announcement> announcements) {
        return announcements.stream().map(announcement ->
                new AnnouncementDeadlineRow(announcement, 0, announcement.getApplicationEndDate())).toList();
    }

    @Test
    void 최신순_다음_페이지는_날짜가_바뀌어도_첫_페이지의_기준일로_마감과_총건수를_계산한다() {
        LocalDate asOf = LocalDate.of(2026, 8, 9);
        LocalDate deadline = LocalDate.of(2026, 8, 11);
        LocalDate posted = LocalDate.of(2026, 8, 5);
        Announcement first = withId(createAnnouncement("first", null, null, AnnouncementPublicationType.ORIGINAL,
                posted, asOf, deadline, null, null, 0L));
        Announcement second = withId(createAnnouncement("second", null, null, AnnouncementPublicationType.ORIGINAL,
                posted, asOf, deadline, null, null, 0L));
        String cursor = announcementCursorCodec.encodeDeadline(asOf, 0, deadline, posted,
                999L, AnnouncementSort.LATEST);
        when(announcementSearchRepository.findSortedLeaves(any(AnnouncementSearchCondition.class), eq(0), eq(deadline),
                eq(posted), eq(999L), eq(2), eq(AnnouncementSort.LATEST)))
                .thenReturn(sortedRows(List.of(first, second)));
        when(announcementSearchRepository.countLatestLeaves(any(AnnouncementSearchCondition.class))).thenReturn(3L);

        AnnouncementListResponse response = announcementQueryService.getAnnouncements(noFilters(), cursor, 1);

        ArgumentCaptor<AnnouncementSearchCondition> condition = ArgumentCaptor.forClass(
                AnnouncementSearchCondition.class);
        verify(announcementSearchRepository).countLatestLeaves(condition.capture());
        assertEquals(asOf, condition.getValue().today());
        assertEquals(3L, response.totalCount());
        assertEquals(2, response.items().getFirst().dDay());
        assertEquals(asOf, announcementCursorCodec.decodeDeadline(response.nextCursor(),
                AnnouncementSort.LATEST).asOf());
    }

    @Test
    void 상세는_정정사유와_조회된_자식_순서와_매칭_요약과_공급조건을_조합한다() {
        Announcement previous = withId(createAnnouncement(
                "previous",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12),
                null,
                null,
                1L
        ));
        Announcement detail = withId(createAnnouncement(
                "detail",
                previous,
                "previous",
                AnnouncementPublicationType.CORRECTION,
                LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12),
                new BigDecimal("1.2500"),
                new BigDecimal("1.5000"),
                12L,
                "접수 일정 정정"
        ));
        AnnouncementSchedule laterSchedule = withId(createSchedule(detail, "서류 제출", 2));
        AnnouncementSchedule earlierSchedule = withId(createSchedule(detail, "인터넷 접수", 1));
        AnnouncementAttachment laterAttachment = withId(createAttachment(detail, "참고.pdf", 2));
        AnnouncementAttachment earlierAttachment = withId(createAttachment(detail, "공고문.pdf", 1));

        HousingComplex complex = withId(createComplex(
                "상세 단지",
                "detail-complex",
                "11",
                "11140",
                "https://example.com/detail-complex.png"
        ));
        HousingType housingType = withId(createHousingType(complex));
        SupplyRow unmatchedRow = withId(createSupplyRow(detail, null, null, "unmatched", 3, null));
        SupplyRow matchedRow = withId(createSupplyRow(detail, complex, housingType, "matched", 1, 5));
        SupplyRow complexOnlyRow = withId(createSupplyRow(detail, complex, null, "complex-only", 2, 0));
        SupplyRow housingTypeOnlyRow = withId(createSupplyRow(
                detail,
                null,
                housingType,
                "housing-type-only",
                3,
                null
        ));

        SupplyTarget duplicateTarget = withId(SupplyTarget.create(
                unmatchedRow,
                "청년",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0
        ));
        SupplyTarget youthTarget = withId(SupplyTarget.create(
                matchedRow,
                "청년",
                "2순위",
                3,
                6,
                new BigDecimal("12000000"),
                new BigDecimal("185000"),
                new BigDecimal("6000000"),
                "만 19세 이상",
                2
        ));
        SupplyTarget newlywedTarget = withId(SupplyTarget.create(
                matchedRow,
                "신혼부부",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1
        ));
        when(announcementRepository.findDetailById(detail.getId())).thenReturn(Optional.of(detail));
        when(announcementScheduleRepository.findAllByAnnouncementIdIn(List.of(detail.getId())))
                .thenReturn(List.of(earlierSchedule, laterSchedule));
        when(announcementAttachmentRepository.findAllByAnnouncementIdIn(List.of(detail.getId())))
                .thenReturn(List.of(earlierAttachment, laterAttachment));
        when(supplyRowRepository.findAllByAnnouncementIdIn(List.of(detail.getId())))
                .thenReturn(List.of(matchedRow, complexOnlyRow, unmatchedRow, housingTypeOnlyRow));
        when(supplyTargetRepository.findAllBySupplyRowIdIn(List.of(
                matchedRow.getId(), complexOnlyRow.getId(), unmatchedRow.getId(), housingTypeOnlyRow.getId()
        ))).thenReturn(List.of(newlywedTarget, youthTarget, duplicateTarget));
        when(regionCodeResolver.resolve("11", "11140")).thenReturn(Optional.of("서울특별시 중구"));

        AnnouncementDetailResponse response = announcementQueryService.getAnnouncement(detail.getId());

        assertEquals(detail.getId(), response.announcementId());
        assertEquals(AnnouncementPublicationType.CORRECTION, response.publicationType());
        assertEquals("접수 일정 정정", response.correctionOrCancellationReason());
        assertEquals(ApplicationStatus.APPLYING, response.applicationStatus());
        assertEquals(RentalType.HAPPY_HOUSING, response.rentalType());
        assertEquals(RecruitmentType.NEW, response.recruitmentType());
        assertEquals("detail 공고", response.title());
        assertEquals(List.of("서울특별시 중구"), response.regionNames());
        assertEquals(AgencyCode.LH, response.agency().code());
        assertEquals("한국토지주택공사", response.agency().name());
        assertEquals(LocalDate.of(2026, 8, 5), response.publishedAt());
        assertEquals(LocalDate.of(2026, 8, 10), response.applicationStartAt());
        assertEquals(LocalDate.of(2026, 8, 12), response.applicationEndAt());
        assertEquals(2, response.dDay());
        assertEquals(LocalDate.of(2026, 9, 1), response.winnerAnnouncementAt());
        assertEquals(12L, response.viewCount());
        assertEquals(List.of("신혼부부", "청년"), response.targets());
        assertEquals(1, response.supplyComplexCount());
        assertEquals(5, response.supplyHouseholdCount());
        assertEquals("https://example.com/announcements/detail", response.documentLinkUrl());

        assertEquals(1, response.receptionPlaces().size());
        assertEquals("LH 청약센터", response.receptionPlaces().getFirst().name());
        assertEquals(ReceptionMethod.ONLINE, response.receptionPlaces().getFirst().method());
        assertNull(response.receptionPlaces().getFirst().address());
        assertEquals("1600-1004", response.receptionPlaces().getFirst().phoneNumber());
        assertEquals("https://apply.lh.or.kr", response.receptionPlaces().getFirst().url());

        assertEquals(
                List.of(earlierSchedule.getId(), laterSchedule.getId()),
                response.schedules().stream().map(schedule -> schedule.scheduleId()).toList()
        );
        assertEquals(ScheduleType.APPLICATION, response.schedules().getFirst().type());
        assertEquals("인터넷 접수", response.schedules().getFirst().name());
        assertEquals(LocalDateTime.of(2026, 8, 10, 9, 0), response.schedules().getFirst().startAt());
        assertEquals(LocalDateTime.of(2026, 8, 10, 18, 0), response.schedules().getFirst().endAt());

        assertEquals(
                List.of(earlierAttachment.getId(), laterAttachment.getId()),
                response.attachments().stream().map(attachment -> attachment.attachmentId()).toList()
        );
        assertEquals("공고문.pdf", response.attachments().getFirst().fileName());
        assertEquals(AttachmentType.ANNOUNCEMENT, response.attachments().getFirst().fileType());
        assertEquals(
                "https://example.com/files/공고문.pdf",
                response.attachments().getFirst().fileUrl()
        );

        assertEquals(
                List.of(
                        matchedRow.getId(),
                        complexOnlyRow.getId(),
                        unmatchedRow.getId(),
                        housingTypeOnlyRow.getId()
                ),
                response.supplyRows().stream().map(SupplyRowResponse::supplyRowId).toList()
        );
        SupplyRowResponse matchedResponse = response.supplyRows().getFirst();
        assertEquals("원문 단지 matched", matchedResponse.sourceComplexName());
        assertEquals("원문 주택형 matched", matchedResponse.sourceHousingTypeName());
        assertEquals(complex.getId(), matchedResponse.complex().complexId());
        assertEquals("상세 단지", matchedResponse.complex().name());
        assertEquals("서울특별시 중구 세종대로 110", matchedResponse.complex().address());
        assertEquals(100, matchedResponse.complex().totalHouseholdCount());
        assertEquals("https://example.com/detail-complex.png", matchedResponse.complex().overviewImageUrl());
        assertEquals(housingType.getId(), matchedResponse.housingType().housingTypeId());
        assertEquals("36A", matchedResponse.housingType().name());
        assertEquals(new BigDecimal("36.00"), matchedResponse.housingType().exclusiveArea());
        assertEquals(new BigDecimal("48.00"), matchedResponse.housingType().supplyArea());
        assertEquals("https://example.com/floor-plan.png", matchedResponse.housingType().floorPlanImageUrl());
        assertNull(matchedResponse.housingType().floorPlan3dImageUrl());
        assertEquals(YearMonth.of(2027, 3), matchedResponse.occupancyExpectedYearMonth());
        assertEquals(SupplyType.NEW, matchedResponse.supplyType());
        assertEquals(5, matchedResponse.totalSupplyHouseholdCount());

        assertEquals(2, matchedResponse.targets().size());
        SupplyTargetResponse nullableTargetResponse = matchedResponse.targets().getFirst();
        assertEquals(newlywedTarget.getId(), nullableTargetResponse.supplyTargetId());
        assertEquals("신혼부부", nullableTargetResponse.target());
        assertNull(nullableTargetResponse.priority());
        assertNull(nullableTargetResponse.supplyHouseholdCount());
        assertNull(nullableTargetResponse.waitlistCount());
        assertNull(nullableTargetResponse.deposit());
        assertNull(nullableTargetResponse.monthlyRent());
        assertNull(nullableTargetResponse.convertibleDeposit());
        assertNull(nullableTargetResponse.applicationCondition());

        SupplyTargetResponse exactMoneyResponse = matchedResponse.targets().get(1);
        assertEquals(youthTarget.getId(), exactMoneyResponse.supplyTargetId());
        assertEquals("2순위", exactMoneyResponse.priority());
        assertEquals(3, exactMoneyResponse.supplyHouseholdCount());
        assertEquals(6, exactMoneyResponse.waitlistCount());
        assertEquals(12_000_000L, exactMoneyResponse.deposit());
        assertEquals(185_000L, exactMoneyResponse.monthlyRent());
        assertEquals(6_000_000L, exactMoneyResponse.convertibleDeposit());
        assertEquals("만 19세 이상", exactMoneyResponse.applicationCondition());

        assertEquals(complex.getId(), response.supplyRows().get(1).complex().complexId());
        assertNull(response.supplyRows().get(1).housingType());
        assertNull(response.supplyRows().get(2).complex());
        assertNull(response.supplyRows().get(2).housingType());
        assertEquals(duplicateTarget.getId(), response.supplyRows().get(2).targets().getFirst().supplyTargetId());
        assertNull(response.supplyRows().get(3).complex());
        assertEquals(housingType.getId(), response.supplyRows().get(3).housingType().housingTypeId());
        assertEquals(new BigDecimal("1.2500"), response.competition().actualRate());
        assertEquals(new BigDecimal("1.5000"), response.competition().predictedRate());
        assertEquals(12L, detail.getViewCount());
    }

    @Test
    void 자식과_경쟁률이_없는_상세도_빈_배열과_존재하는_경쟁률_객체를_반환한다() {
        Announcement announcement = withId(createAnnouncement(
                "empty-detail",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 10),
                null,
                null,
                0L
        ));
        when(announcementRepository.findDetailById(announcement.getId())).thenReturn(Optional.of(announcement));

        AnnouncementDetailResponse response = announcementQueryService.getAnnouncement(announcement.getId());

        assertEquals(List.of(), response.regionNames());
        assertEquals(List.of(), response.targets());
        assertEquals(List.of(), response.schedules());
        assertEquals(List.of(), response.attachments());
        assertEquals(List.of(), response.supplyRows());
        assertEquals(0, response.supplyComplexCount());
        assertNull(response.supplyHouseholdCount());
        assertNull(response.competition().actualRate());
        assertNull(response.competition().predictedRate());
    }

    @Test
    void 접수처가_없는_공고_상세는_빈_접수처_목록을_반환한다() {
        Announcement announcement = withId(Announcement.create(
                "without-reception-place",
                null,
                null,
                "접수처 없는 공고",
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/without-reception-place",
                null,
                0L,
                null
        ));
        when(announcementRepository.findDetailById(announcement.getId())).thenReturn(Optional.of(announcement));

        AnnouncementDetailResponse response = announcementQueryService.getAnnouncement(announcement.getId());

        assertEquals(List.of(), response.receptionPlaces());
    }

    @Test
    void 없는_공고_상세는_기능_예외로_실패한다() {
        AnnouncementNotFoundException exception = assertThrows(
                AnnouncementNotFoundException.class,
                () -> announcementQueryService.getAnnouncement(Long.MAX_VALUE)
        );

        assertEquals("모집 공고를 찾을 수 없습니다.", exception.getMessage());
    }

    private Announcement createAnnouncement(
            String sourceIdentifier,
            Announcement previousAnnouncement,
            String previousSourceIdentifier,
            AnnouncementPublicationType publicationType,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            BigDecimal actualCompetitionRate,
            BigDecimal predictedCompetitionRate,
            long viewCount
    ) {
        return createAnnouncement(
                sourceIdentifier,
                previousAnnouncement,
                previousSourceIdentifier,
                publicationType,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                actualCompetitionRate,
                predictedCompetitionRate,
                viewCount,
                null
        );
    }

    private Announcement createAnnouncement(
            String sourceIdentifier,
            Announcement previousAnnouncement,
            String previousSourceIdentifier,
            AnnouncementPublicationType publicationType,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            BigDecimal actualCompetitionRate,
            BigDecimal predictedCompetitionRate,
            long viewCount,
            String reason
    ) {
        return Announcement.create(
                sourceIdentifier,
                previousSourceIdentifier,
                previousAnnouncement,
                sourceIdentifier + " 공고",
                publicationType,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/" + sourceIdentifier,
                reason,
                viewCount,
                actualCompetitionRate,
                predictedCompetitionRate,
                ReceptionPlace.create(
                        "LH 청약센터",
                        ReceptionMethod.ONLINE,
                        null,
                        "1600-1004",
                        "https://apply.lh.or.kr"
                )
        );
    }

    private HousingComplex createComplex(
            String name,
            String sourceIdentifier,
            String provinceCode,
            String districtCode,
            String imageUrl
    ) {
        return HousingComplex.create(
                name,
                sourceIdentifier,
                "행복주택",
                Address.create(
                        roadAddress(provinceCode),
                        districtCode + "10100100010000",
                        districtCode + "10100",
                        provinceCode,
                        districtCode,
                        new BigDecimal("37.566500"),
                        new BigDecimal("126.978000")
                ),
                100,
                "LH",
                LocalDate.of(2020, 6, 30),
                "개별난방",
                "아파트",
                "계단식",
                true,
                80,
                imageUrl,
                null
        );
    }

    private HousingType createHousingType(HousingComplex housingComplex) {
        return HousingType.create(
                housingComplex,
                "36A",
                new BigDecimal("36.00"),
                new BigDecimal("48.00"),
                20,
                "https://example.com/floor-plan.png",
                false,
                null
        );
    }

    private SupplyRow createSupplyRow(
            Announcement announcement,
            HousingComplex housingComplex,
            HousingType housingType,
            String sourceIdentifier,
            int displayOrder,
            Integer totalSupplyHouseholdCount
    ) {
        return SupplyRow.create(
                announcement,
                housingComplex,
                housingType,
                sourceIdentifier,
                displayOrder,
                "원문 단지 " + sourceIdentifier,
                "원문 주택형 " + sourceIdentifier,
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                matchingFailureReason(housingComplex),
                totalSupplyHouseholdCount
        );
    }

    private String roadAddress(String provinceCode) {
        if (provinceCode.equals("11")) {
            return "서울특별시 중구 세종대로 110";
        }
        if (provinceCode.equals("26")) {
            return "부산광역시 중구 중앙대로 100";
        }
        return "알 수 없는 주소";
    }

    private String matchingFailureReason(HousingComplex housingComplex) {
        if (housingComplex == null) {
            return "단지 매칭 실패";
        }
        return null;
    }

    private AnnouncementSchedule createSchedule(Announcement announcement, String name, int displayOrder) {
        return AnnouncementSchedule.create(
                announcement,
                ScheduleType.APPLICATION,
                name,
                LocalDateTime.of(2026, 8, 10, 9, 0),
                LocalDateTime.of(2026, 8, 10, 18, 0),
                displayOrder
        );
    }

    private AnnouncementAttachment createAttachment(
            Announcement announcement,
            String fileName,
            int displayOrder
    ) {
        return AnnouncementAttachment.create(
                announcement,
                fileName,
                AttachmentType.ANNOUNCEMENT,
                "https://example.com/files/" + fileName,
                displayOrder
        );
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-09T15:00:00Z"), SEOUL);
    }

    private AnnouncementSearchRequest noFilters() {
        return new AnnouncementSearchRequest(null, null, null, null, null, null, null, null, null);
    }

    private AnnouncementSearchRequest requestWithPeriod(LocalDate applicationFrom, LocalDate applicationTo) {
        return new AnnouncementSearchRequest(
                null, null, null, null, null, null, null, applicationFrom, applicationTo
        );
    }

    private AnnouncementSearchRequest requestWithPublicationTypes(
            List<AnnouncementPublicationType> publicationTypes
    ) {
        return new AnnouncementSearchRequest(null, null, null, null, publicationTypes, null, null, null, null);
    }

    private AnnouncementSearchRequest requestWithApplicationStatuses(List<ApplicationStatus> applicationStatuses) {
        return new AnnouncementSearchRequest(null, null, null, applicationStatuses, null, null, null, null, null);
    }

    private AnnouncementSearchRequest requestWithRegion(String regionCode) {
        return new AnnouncementSearchRequest(null, regionCode, null, null, null, null, null, null, null);
    }

    private AnnouncementQueryService searchService(
            AnnouncementSearchRepository announcementSearchRepository,
            RegionCodeResolver regionCodeResolver
    ) {
        return searchService(announcementSearchRepository, regionCodeResolver, mock(AnnouncementResponseMapper.class));
    }

    private AnnouncementQueryService searchService(
            AnnouncementSearchRepository announcementSearchRepository,
            RegionCodeResolver regionCodeResolver,
            AnnouncementResponseMapper announcementResponseMapper
    ) {
        return new AnnouncementQueryService(
                mock(AnnouncementRepository.class),
                mock(AnnouncementApplicationScheduleRepository.class),
                announcementSearchRepository,
                mock(AnnouncementScheduleRepository.class),
                mock(AnnouncementAttachmentRepository.class),
                mock(SupplyRowRepository.class),
                mock(SupplyTargetRepository.class),
                announcementResponseMapper,
                new AnnouncementCursorCodec(),
                fixedClock(),
                regionCodeResolver
        );
    }

    private <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", ++nextId);
        return entity;
    }
}
