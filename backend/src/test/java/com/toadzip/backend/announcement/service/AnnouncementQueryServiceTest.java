package com.toadzip.backend.announcement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.toadzip.backend.announcement.domain.Announcement;
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
import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListItemResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListResponse;
import com.toadzip.backend.announcement.dto.response.SupplyRowResponse;
import com.toadzip.backend.announcement.dto.response.SupplyTargetResponse;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@SpringBootTest
@ActiveProfiles("test")
@Import(AnnouncementQueryServiceTest.FixedClockConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AnnouncementQueryServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private AnnouncementQueryService announcementQueryService;

    @Autowired
    private AnnouncementCursorCodec announcementCursorCodec;

    @Test
    void 목록은_최신_리비전과_동률_ID_순서로_상태와_집계를_반환한다(CapturedOutput output) {
        Announcement original = persist(createAnnouncement(
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
        Announcement correction = persist(createAnnouncement(
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
        Announcement cancellation = persist(createAnnouncement(
                "cancellation",
                null,
                null,
                AnnouncementPublicationType.CANCELLATION,
                LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 10),
                null,
                null,
                9L
        ));
        Announcement applying = persist(createAnnouncement(
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
        Announcement beforeApplication = persist(createAnnouncement(
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
        Announcement closed = persist(createAnnouncement(
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

        HousingComplex seoulComplex = persist(createComplex(
                "서울 단지",
                "seoul-complex",
                "11",
                "11140",
                null
        ));
        HousingComplex busanComplex = persist(createComplex(
                "부산 단지",
                "busan-complex",
                "26",
                "26110",
                "https://example.com/busan.png"
        ));
        HousingComplex unresolvedComplex = persist(createComplex(
                "미해결 단지",
                "unresolved-complex",
                "99",
                "99999",
                null
        ));
        persist(createSupplyRow(correction, seoulComplex, null, "row-1", 0, null));
        persist(createSupplyRow(correction, seoulComplex, null, "row-2", 1, 0));
        persist(createSupplyRow(correction, busanComplex, null, "row-3", 2, 7));
        persist(createSupplyRow(correction, null, null, "row-4", 3, 3));
        persist(createSupplyRow(correction, unresolvedComplex, null, "row-5", 4, null));
        persist(createSupplyRow(correction, unresolvedComplex, null, "row-6", 5, null));
        persist(createSupplyRow(applying, null, null, "all-null", 0, null));
        persist(createSupplyRow(beforeApplication, null, null, "known-zero", 0, 0));
        entityManager.flush();

        AnnouncementListResponse response = announcementQueryService.getAnnouncements(null, 20);

        assertFalse(response.hasNext());
        assertNull(response.nextCursor());
        assertEquals(
                List.of(
                        cancellation.getId(),
                        correction.getId(),
                        applying.getId(),
                        beforeApplication.getId(),
                        closed.getId()
                ),
                response.items().stream().map(AnnouncementListItemResponse::announcementId).toList()
        );
        assertFalse(response.items().stream().anyMatch(item -> item.announcementId() == original.getId()));

        AnnouncementListItemResponse cancelledItem = response.items().get(0);
        assertEquals(AnnouncementPublicationType.CANCELLATION, cancelledItem.publicationType());
        assertEquals(ApplicationStatus.CANCELLED, cancelledItem.applicationStatus());
        assertNull(cancelledItem.dDay());

        AnnouncementListItemResponse correctionItem = response.items().get(1);
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

        assertEquals(ApplicationStatus.APPLYING, response.items().get(2).applicationStatus());
        assertEquals(1, response.items().get(2).dDay());
        assertNull(response.items().get(2).supplyHouseholdCount());
        assertEquals(ApplicationStatus.BEFORE_APPLICATION, response.items().get(3).applicationStatus());
        assertEquals(2, response.items().get(3).dDay());
        assertEquals(0, response.items().get(3).supplyHouseholdCount());
        assertEquals(ApplicationStatus.CLOSED, response.items().get(4).applicationStatus());
        assertNull(response.items().get(4).dDay());
        assertEquals(37L, entityManager.find(Announcement.class, correction.getId()).getViewCount());
        assertEquals(
                1L,
                output.getAll().lines()
                        .filter(line -> line.contains(
                                "provinceCode=99, cityCountyDistrictCode=99999"
                        ))
                        .count()
        );
    }

    @Test
    void 목록은_추가_한_건으로_다음_페이지를_판단하고_마지막_반환항목을_커서로_쓴다() {
        Announcement first = persist(createAnnouncement(
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
        Announcement second = persist(createAnnouncement(
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
        entityManager.flush();

        AnnouncementListResponse firstPage = announcementQueryService.getAnnouncements(null, 1);
        AnnouncementListResponse secondPage = announcementQueryService.getAnnouncements(firstPage.nextCursor(), 1);

        assertTrue(firstPage.hasNext());
        assertEquals(second.getId(), firstPage.items().getFirst().announcementId());
        assertEquals(
                announcementCursorCodec.encode(second.getPostedDate(), second.getId()),
                firstPage.nextCursor()
        );
        assertFalse(secondPage.hasNext());
        assertNull(secondPage.nextCursor());
        assertEquals(first.getId(), secondPage.items().getFirst().announcementId());
    }

    @Test
    void 잘못된_목록_크기는_저장소_접근_전에_거부한다() {
        AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
        AnnouncementQueryService service = new AnnouncementQueryService(
                announcementRepository,
                mock(AnnouncementScheduleRepository.class),
                mock(AnnouncementAttachmentRepository.class),
                mock(SupplyRowRepository.class),
                mock(SupplyTargetRepository.class),
                mock(RegionCodeResolver.class),
                new AnnouncementCursorCodec(),
                fixedClock()
        );

        assertThrows(InvalidAnnouncementRequestException.class, () -> service.getAnnouncements(null, 0));
        assertThrows(InvalidAnnouncementRequestException.class, () -> service.getAnnouncements(null, 51));
        verifyNoInteractions(announcementRepository);
    }

    @Test
    void 상세는_리비전과_자식_순서와_매칭_요약과_공급조건을_조합한다() {
        Announcement previous = persist(createAnnouncement(
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
        Announcement detail = persist(createAnnouncement(
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
        AnnouncementSchedule laterSchedule = persist(createSchedule(detail, "서류 제출", 2));
        AnnouncementSchedule earlierSchedule = persist(createSchedule(detail, "인터넷 접수", 1));
        AnnouncementAttachment laterAttachment = persist(createAttachment(detail, "참고.pdf", 2));
        AnnouncementAttachment earlierAttachment = persist(createAttachment(detail, "공고문.pdf", 1));

        HousingComplex complex = persist(createComplex(
                "상세 단지",
                "detail-complex",
                "11",
                "11140",
                "https://example.com/detail-complex.png"
        ));
        HousingType housingType = persist(createHousingType(complex));
        SupplyRow unmatchedRow = persist(createSupplyRow(detail, null, null, "unmatched", 3, null));
        SupplyRow matchedRow = persist(createSupplyRow(detail, complex, housingType, "matched", 1, 5));
        SupplyRow complexOnlyRow = persist(createSupplyRow(detail, complex, null, "complex-only", 2, 0));
        SupplyRow housingTypeOnlyRow = persist(createSupplyRow(
                detail,
                null,
                housingType,
                "housing-type-only",
                3,
                null
        ));

        SupplyTarget duplicateTarget = persist(SupplyTarget.create(
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
        SupplyTarget youthTarget = persist(SupplyTarget.create(
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
        SupplyTarget newlywedTarget = persist(SupplyTarget.create(
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
        entityManager.flush();

        AnnouncementDetailResponse response = announcementQueryService.getAnnouncement(detail.getId());

        assertEquals(detail.getId(), response.announcementId());
        assertEquals(previous.getId(), response.previousAnnouncementId());
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
        assertEquals(12L, entityManager.find(Announcement.class, detail.getId()).getViewCount());
    }

    @Test
    void 자식과_경쟁률이_없는_상세도_빈_배열과_존재하는_경쟁률_객체를_반환한다() {
        Announcement announcement = persist(createAnnouncement(
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
        entityManager.flush();

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

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedAnnouncementClock() {
            return Clock.fixed(Instant.parse("2026-08-09T15:00:00Z"), SEOUL);
        }
    }
}
