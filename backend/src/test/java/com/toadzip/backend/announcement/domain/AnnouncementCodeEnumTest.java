package com.toadzip.backend.announcement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import org.junit.jupiter.api.Test;

class AnnouncementCodeEnumTest {

    @Test
    void 저장코드는_정식_코드와_명시된_기존_코드를_정확히_변환한다() {
        assertEquals(AnnouncementPublicationType.ORIGINAL, AnnouncementPublicationType.fromStoredValue("ORIGINAL"));
        assertEquals(AnnouncementPublicationType.ORIGINAL, AnnouncementPublicationType.fromStoredValue("원공고"));
        assertEquals(AnnouncementPublicationType.CORRECTION, AnnouncementPublicationType.fromStoredValue("정정공고"));
        assertEquals(AnnouncementPublicationType.CANCELLATION, AnnouncementPublicationType.fromStoredValue("취소공고"));
        assertEquals(RentalType.HAPPY_HOUSING, RentalType.fromStoredValue("행복주택"));
        assertEquals(RentalType.NATIONAL_RENTAL, RentalType.fromStoredValue("국민임대"));
        assertEquals(RentalType.PERMANENT_RENTAL, RentalType.fromStoredValue("영구임대"));
        assertEquals(RentalType.PUBLIC_RENTAL_50Y, RentalType.fromStoredValue("50년공공임대"));
        assertEquals(RentalType.INTEGRATED_PUBLIC_RENTAL, RentalType.fromStoredValue("통합공공임대"));
        assertEquals(RentalType.REDEVELOPMENT_RENTAL, RentalType.fromStoredValue("재개발임대"));
        assertEquals(RentalType.ETC, RentalType.fromStoredValue("기타"));
        assertEquals(RecruitmentType.NEW, RecruitmentType.fromStoredValue("신규모집"));
        assertEquals(RecruitmentType.WAITLIST, RecruitmentType.fromStoredValue("예비입주자"));
        assertEquals(RecruitmentType.ETC, RecruitmentType.fromStoredValue("기타"));
        assertEquals(ReceptionMethod.ONLINE, ReceptionMethod.fromStoredValue("인터넷"));
        assertEquals(ReceptionMethod.VISIT, ReceptionMethod.fromStoredValue("현장"));
        assertEquals(ReceptionMethod.MAIL, ReceptionMethod.fromStoredValue("우편"));
        assertEquals(ReceptionMethod.ETC, ReceptionMethod.fromStoredValue("기타"));
        assertEquals(ScheduleType.APPLICATION, ScheduleType.fromStoredValue("접수"));
        assertEquals(ScheduleType.DOCUMENT_SUBMISSION, ScheduleType.fromStoredValue("서류제출"));
        assertEquals(ScheduleType.WINNER_ANNOUNCEMENT, ScheduleType.fromStoredValue("당첨자발표"));
        assertEquals(ScheduleType.CONTRACT, ScheduleType.fromStoredValue("계약"));
        assertEquals(ScheduleType.MOVE_IN, ScheduleType.fromStoredValue("입주"));
        assertEquals(ScheduleType.ETC, ScheduleType.fromStoredValue("기타"));
        assertEquals(AttachmentType.ANNOUNCEMENT, AttachmentType.fromStoredValue("공고문"));
        assertEquals(AttachmentType.CORRECTION, AttachmentType.fromStoredValue("정정공고"));
        assertEquals(AttachmentType.CANCELLATION, AttachmentType.fromStoredValue("취소공고"));
        assertEquals(AttachmentType.REFERENCE, AttachmentType.fromStoredValue("참고자료"));
        assertEquals(AttachmentType.ETC, AttachmentType.fromStoredValue("기타"));
        assertEquals(AgencyCode.LH, AgencyCode.fromStoredValue("한국토지주택공사"));
        assertEquals(AgencyCode.SH, AgencyCode.fromStoredValue("서울주택도시공사"));
        assertEquals(AgencyCode.GH, AgencyCode.fromStoredValue("경기주택도시공사"));
        assertEquals(AgencyCode.ETC, AgencyCode.fromStoredValue("기타"));
    }

    @Test
    void 알수없는_저장코드는_기타로_대체하지_않는다() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleType.fromStoredValue("임의 일정"));
    }

    @Test
    void 모든_정식_코드를_선언한다() {
        assertEquals(
                7,
                RentalType.values().length
        );
        assertEquals(3, AnnouncementPublicationType.values().length);
        assertEquals(4, ApplicationStatus.values().length);
        assertEquals(3, RecruitmentType.values().length);
        assertEquals(4, ReceptionMethod.values().length);
        assertEquals(6, ScheduleType.values().length);
        assertEquals(5, AttachmentType.values().length);
        assertEquals(2, SupplyType.values().length);
        assertEquals(4, AgencyCode.values().length);
    }

    @Test
    void 기관코드의_표시명을_제공한다() {
        assertEquals("한국토지주택공사", AgencyCode.LH.displayName());
        assertEquals("서울주택도시공사", AgencyCode.SH.displayName());
        assertEquals("경기주택도시공사", AgencyCode.GH.displayName());
        assertEquals("기타", AgencyCode.ETC.displayName());
    }
}
