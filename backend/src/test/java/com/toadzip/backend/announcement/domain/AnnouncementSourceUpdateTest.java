package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnnouncementSourceUpdateTest {

    @Test
    void 원천에서_변경된_공고_정보를_갱신한다() {
        Announcement announcement = announcement("기존 공고명");
        ReceptionPlace receptionPlace = ReceptionPlace.create(
                "SH 인터넷청약",
                ReceptionMethod.ONLINE,
                null,
                "1600-3456",
                "https://example.com/apply"
        );

        boolean updated = announcement.updateFromSource(
                null,
                null,
                "변경 공고명",
                AnnouncementPublicationType.ORIGINAL,
                RentalType.NATIONAL_RENTAL,
                RecruitmentType.WAITLIST,
                AgencyCode.SH,
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 15),
                LocalDate.of(2026, 9, 2),
                "https://example.com/announcements/changed",
                null,
                receptionPlace
        );

        assertThat(updated).isTrue();
        assertThat(announcement.getName()).isEqualTo("변경 공고명");
        assertThat(announcement.getRecruitmentType()).isEqualTo(RecruitmentType.WAITLIST);
        assertThat(announcement.getProvider()).isEqualTo(AgencyCode.SH);
        assertThat(announcement.getReceptionPlace()).isEqualTo(receptionPlace);
    }

    @Test
    void 원천_공고_정보가_같으면_갱신하지_않는다() {
        Announcement announcement = announcement("기존 공고명");

        boolean updated = announcement.updateFromSource(
                null,
                null,
                "기존 공고명",
                AnnouncementPublicationType.ORIGINAL,
                RentalType.NATIONAL_RENTAL,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/1",
                null,
                receptionPlace()
        );

        assertThat(updated).isFalse();
    }

    private Announcement announcement(String name) {
        return Announcement.create(
                "source-announcement-id",
                null,
                null,
                name,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.NATIONAL_RENTAL,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/1",
                null,
                0L,
                receptionPlace()
        );
    }

    private ReceptionPlace receptionPlace() {
        return ReceptionPlace.create(
                "LH 청약센터",
                ReceptionMethod.ONLINE,
                null,
                "1600-1004",
                "https://example.com/apply"
        );
    }
}
