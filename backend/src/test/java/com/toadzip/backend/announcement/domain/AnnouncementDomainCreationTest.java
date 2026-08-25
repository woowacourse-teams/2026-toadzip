package com.toadzip.backend.announcement.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class AnnouncementDomainCreationTest {

    @Test
    void 공고_접수처를_생성한다() {
        Method createMethod = findCreateMethod(
                ReceptionPlace.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class
        );

        ReceptionPlace receptionPlace = invoke(
                createMethod,
                "LH 청약센터",
                "인터넷",
                null,
                "1600-1004",
                "https://apply.lh.or.kr"
        );

        assertEquals("LH 청약센터", receptionPlace.getName());
        assertEquals("인터넷", receptionPlace.getMethod());
        assertNull(receptionPlace.getAddress());
        assertEquals("1600-1004", receptionPlace.getContact());
        assertEquals("https://apply.lh.or.kr", receptionPlace.getUrl());
    }

    @Test
    void 원공고와_정정공고를_각각_생성한다() {
        Announcement previousAnnouncement = new Announcement();
        ReceptionPlace receptionPlace = new ReceptionPlace();
        LocalDate postedDate = LocalDate.of(2026, 8, 1);
        LocalDate applicationStartDate = LocalDate.of(2026, 8, 10);
        LocalDate applicationEndDate = LocalDate.of(2026, 8, 14);
        LocalDate winnerAnnouncementDate = LocalDate.of(2026, 9, 1);
        Method createMethod = findCreateMethod(
                Announcement.class,
                String.class,
                String.class,
                Announcement.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                LocalDate.class,
                LocalDate.class,
                LocalDate.class,
                LocalDate.class,
                String.class,
                String.class,
                long.class,
                ReceptionPlace.class
        );

        Announcement announcement = invoke(
                createMethod,
                "source-announcement-id-2",
                "source-announcement-id-1",
                previousAnnouncement,
                "행복주택 정정 모집공고",
                "정정공고",
                "행복주택",
                "신규모집",
                "LH",
                postedDate,
                applicationStartDate,
                applicationEndDate,
                winnerAnnouncementDate,
                "https://example.com/announcements/2",
                "접수일 변경",
                100L,
                receptionPlace
        );

        assertEquals("source-announcement-id-2", announcement.getSourceAnnouncementIdentifier());
        assertEquals("source-announcement-id-1", announcement.getPreviousSourceAnnouncementIdentifier());
        assertEquals(previousAnnouncement, announcement.getPreviousAnnouncement());
        assertEquals("행복주택 정정 모집공고", announcement.getName());
        assertEquals("정정공고", announcement.getStatus());
        assertEquals("행복주택", announcement.getSupplyType());
        assertEquals("신규모집", announcement.getRecruitmentType());
        assertEquals("LH", announcement.getProvider());
        assertEquals(postedDate, announcement.getPostedDate());
        assertEquals(applicationStartDate, announcement.getApplicationStartDate());
        assertEquals(applicationEndDate, announcement.getApplicationEndDate());
        assertEquals(winnerAnnouncementDate, announcement.getWinnerAnnouncementDate());
        assertEquals("https://example.com/announcements/2", announcement.getOriginalUrl());
        assertEquals("접수일 변경", announcement.getCorrectionCancellationReason());
        assertEquals(100L, announcement.getViewCount());
        assertEquals(receptionPlace, announcement.getReceptionPlace());
    }

    @Test
    void 매칭되지_않은_원천_공급행도_생성한다() {
        Announcement announcement = new Announcement();
        YearMonth expectedMoveInMonth = YearMonth.of(2027, 3);
        Method createMethod = findCreateMethod(
                SupplyRow.class,
                Announcement.class,
                HousingComplex.class,
                HousingType.class,
                String.class,
                int.class,
                String.class,
                String.class,
                String.class,
                YearMonth.class,
                SupplyCategory.class,
                String.class,
                int.class
        );

        SupplyRow supplyRow = invoke(
                createMethod,
                announcement,
                null,
                null,
                "source-supply-row-id",
                1,
                "원천 단지명",
                "36A",
                "1114010100100010000",
                expectedMoveInMonth,
                SupplyCategory.NEW_SUPPLY,
                "단지 식별자 불일치",
                20
        );

        assertEquals(announcement, supplyRow.getAnnouncement());
        assertNull(supplyRow.getHousingComplex());
        assertNull(supplyRow.getHousingType());
        assertEquals("source-supply-row-id", supplyRow.getSourceSupplyRowIdentifier());
        assertEquals(1, supplyRow.getDisplayOrder());
        assertEquals("원천 단지명", supplyRow.getSourceComplexName());
        assertEquals("36A", supplyRow.getSourceHousingTypeName());
        assertEquals("1114010100100010000", supplyRow.getSupplyPnu());
        assertEquals(expectedMoveInMonth, supplyRow.getExpectedMoveInMonth());
        assertEquals(SupplyCategory.NEW_SUPPLY, supplyRow.getSupplyCategory());
        assertEquals("단지 식별자 불일치", supplyRow.getMatchingFailureReason());
        assertEquals(20, supplyRow.getTotalSupplyHouseholdCount());
    }

    @Test
    void 신청_대상별_공급정보를_생성한다() {
        SupplyRow supplyRow = new SupplyRow();
        BigDecimal rentalDeposit = new BigDecimal("50000000");
        BigDecimal monthlyRent = new BigDecimal("250000");
        BigDecimal convertedDeposit = new BigDecimal("70000000");
        Method createMethod = findCreateMethod(
                SupplyTarget.class,
                SupplyRow.class,
                String.class,
                String.class,
                int.class,
                int.class,
                BigDecimal.class,
                BigDecimal.class,
                BigDecimal.class,
                String.class,
                int.class
        );

        SupplyTarget supplyTarget = invoke(
                createMethod,
                supplyRow,
                "청년",
                "1순위",
                10,
                20,
                rentalDeposit,
                monthlyRent,
                convertedDeposit,
                "소득 기준 충족",
                1
        );

        assertEquals(supplyRow, supplyTarget.getSupplyRow());
        assertEquals("청년", supplyTarget.getTarget());
        assertEquals("1순위", supplyTarget.getSupplyRank());
        assertEquals(10, supplyTarget.getSupplyHouseholdCount());
        assertEquals(20, supplyTarget.getReserveCount());
        assertEquals(rentalDeposit, supplyTarget.getRentalDeposit());
        assertEquals(monthlyRent, supplyTarget.getMonthlyRent());
        assertEquals(convertedDeposit, supplyTarget.getConvertedDeposit());
        assertEquals("소득 기준 충족", supplyTarget.getApplicationCondition());
        assertEquals(1, supplyTarget.getDisplayOrder());
    }

    @Test
    void 공고_일정을_생성한다() {
        Announcement announcement = new Announcement();
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 10, 10, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 8, 14, 17, 0);
        Method createMethod = findCreateMethod(
                AnnouncementSchedule.class,
                Announcement.class,
                String.class,
                String.class,
                LocalDateTime.class,
                LocalDateTime.class,
                int.class
        );

        AnnouncementSchedule schedule = invoke(
                createMethod,
                announcement,
                "접수",
                "인터넷 접수",
                startAt,
                endAt,
                1
        );

        assertEquals(announcement, schedule.getAnnouncement());
        assertEquals("접수", schedule.getScheduleType());
        assertEquals("인터넷 접수", schedule.getName());
        assertEquals(startAt, schedule.getStartAt());
        assertEquals(endAt, schedule.getEndAt());
        assertEquals(1, schedule.getDisplayOrder());
    }

    @Test
    void 공고_첨부파일을_생성한다() {
        Announcement announcement = new Announcement();
        Method createMethod = findCreateMethod(
                AnnouncementAttachment.class,
                Announcement.class,
                String.class,
                String.class,
                String.class,
                int.class
        );

        AnnouncementAttachment attachment = invoke(
                createMethod,
                announcement,
                "모집공고문.pdf",
                "공고문",
                "https://example.com/files/announcement.pdf",
                1
        );

        assertEquals(announcement, attachment.getAnnouncement());
        assertEquals("모집공고문.pdf", attachment.getFileName());
        assertEquals("공고문", attachment.getFileType());
        assertEquals("https://example.com/files/announcement.pdf", attachment.getFileUrl());
        assertEquals(1, attachment.getDisplayOrder());
    }

    private Method findCreateMethod(Class<?> type, Class<?>... parameterTypes) {
        return assertDoesNotThrow(() -> type.getDeclaredMethod("create", parameterTypes));
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(Method method, Object... arguments) {
        return assertDoesNotThrow(() -> (T) method.invoke(null, arguments));
    }
}
