package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnnouncementSupplyOwnerTest {

    @Test
    void 정제가_만든_공고는_원천_소유이고_직접_등록_공고는_공고문_소유다() {
        assertThat(announcement("21026").getSupplyOwner()).isEqualTo(SupplyOwner.LH_SOURCE);
        assertThat(announcement("ADMIN_ENTRY-ANNOUNCEMENT-a").getSupplyOwner())
                .isEqualTo(SupplyOwner.ANNOUNCEMENT_DOCUMENT);
    }

    @Test
    void 관리자가_공급정보를_고치면_공고문_소유가_되고_일정만_확정하면_원천_소유로_남는다() {
        Announcement schedule = announcement("21026");
        schedule.recordAdminScheduleChange();
        assertThat(schedule.getSupplyOwner()).isEqualTo(SupplyOwner.LH_SOURCE);
        assertThat(schedule.getAdminUpdatedAt()).isNotNull();

        Announcement supply = announcement("21027");
        supply.recordAdminSupplyChange();
        assertThat(supply.getSupplyOwner()).isEqualTo(SupplyOwner.ANNOUNCEMENT_DOCUMENT);
        assertThat(supply.getAdminUpdatedAt()).isNotNull();
    }

    @Test
    void 출처는_LH_공고_ID_관리자_식별자_순서로_정하고_나머지는_과거_마이홈_공고다() {
        Announcement myHome = announcement("21026");
        assertThat(myHome.origin()).isEqualTo(AnnouncementOrigin.MYHOME_LEGACY);
        myHome.enrichFromLh("P1", null, null);
        assertThat(myHome.origin()).isEqualTo(AnnouncementOrigin.LH_SOURCE);

        Announcement admin = announcement("ADMIN_ENTRY-ANNOUNCEMENT-a");
        assertThat(admin.origin()).isEqualTo(AnnouncementOrigin.ADMIN_ENTRY);
        admin.enrichFromLh("P2", null, null);
        assertThat(admin.origin()).isEqualTo(AnnouncementOrigin.LH_SOURCE);
    }

    private Announcement announcement(String identifier) {
        return Announcement.create(identifier, null, null, "공고", AnnouncementPublicationType.ORIGINAL,
                RentalType.NATIONAL_RENTAL, RecruitmentType.NEW, AgencyCode.LH, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 14), LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/1", null, 0L, null);
    }
}
