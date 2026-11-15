package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnnouncementSupplyConfirmationTest {

    @Test
    void 새_공고는_공급표를_쓰기_전까지_확정하지_않는다() {
        assertThat(announcement("21026").isSupplyConfirmed()).isFalse();
        assertThat(announcement("ADMIN_ENTRY-ANNOUNCEMENT-a").isSupplyConfirmed()).isFalse();
    }

    @Test
    void 정본_확정은_공급표_쓰기만_하고_관리자_저장_표시는_확정하지_않는다() {
        Announcement schedule = announcement("21026");
        schedule.recordAdminScheduleChange();
        assertThat(schedule.isSupplyConfirmed()).isFalse();
        assertThat(schedule.getAdminUpdatedAt()).isNotNull();

        Announcement review = announcement("21028");
        review.recordAdminDocumentReview();
        assertThat(review.isSupplyConfirmed()).isFalse();

        // 공급정보 저장은 공급표 쓰기(SupplySheetStore.replace)가 확정한다. 저장 표시만으로는 확정하지 않는다.
        Announcement supply = announcement("21027");
        supply.recordAdminSupplyChange();
        assertThat(supply.isSupplyConfirmed()).isFalse();
        assertThat(supply.getAdminUpdatedAt()).isNotNull();
    }

    @Test
    void 확정한_뒤에만_원천_지문_변경이_재확인을_켠다() {
        Announcement staged = announcement("21026");
        staged.observeAgencySupply("a");
        assertThat(staged.observeAgencySupply("b")).isFalse();
        assertThat(staged.isSupplyRecheckRequired()).isFalse();

        Announcement confirmed = announcement("21027");
        confirmed.observeAgencySupply("a");
        confirmed.confirmSupply();
        assertThat(confirmed.observeAgencySupply("a")).isFalse();
        assertThat(confirmed.observeAgencySupply("b")).isTrue();
        assertThat(confirmed.isSupplyRecheckRequired()).isTrue();
        confirmed.recordAdminDocumentReview();
        assertThat(confirmed.isSupplyRecheckRequired()).isFalse();
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

    @Test
    void GH와_SH_원천으로_만든_공고는_기관_원천_출처다() {
        assertThat(announcement("GH:801").origin()).isEqualTo(AnnouncementOrigin.GH_SOURCE);
        assertThat(announcement("SH:309337").origin()).isEqualTo(AnnouncementOrigin.SH_SOURCE);
        assertThat(announcement("GHX801").origin()).isEqualTo(AnnouncementOrigin.MYHOME_LEGACY);
    }

    private Announcement announcement(String identifier) {
        return Announcement.create(identifier, null, null, "공고", AnnouncementPublicationType.ORIGINAL,
                RentalType.NATIONAL_RENTAL, RecruitmentType.NEW, AgencyCode.LH, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 14), LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/1", null, 0L, null);
    }
}
