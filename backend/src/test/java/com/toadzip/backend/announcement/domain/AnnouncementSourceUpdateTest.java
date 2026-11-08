package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import java.time.LocalDate;
import java.util.List;
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

        boolean updated = announcement.updateFromMyHome(
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
                List.of(receptionPlace)
        );

        assertThat(updated).isTrue();
        assertThat(announcement.getName()).isEqualTo("변경 공고명");
        assertThat(announcement.getRecruitmentType()).isEqualTo(RecruitmentType.WAITLIST);
        assertThat(announcement.getProvider()).isEqualTo(AgencyCode.SH);
        assertThat(announcement.getReceptionPlaces()).containsExactly(receptionPlace);
    }

    @Test
    void 접수처_목록은_순서대로_비교해_두번째_접수처만_바뀌어도_갱신한다() {
        ReceptionPlace online = receptionPlace();
        ReceptionPlace visit = ReceptionPlace.create("행복주택 견본주택", ReceptionMethod.VISIT, "서울시 강남구 1",
                "02-000-0000", null, "인터넷 취약계층만 현장 접수");
        Announcement announcement = announcement("기존 공고명", List.of(online, visit));
        ReceptionPlace movedVisit = ReceptionPlace.create("행복주택 견본주택", ReceptionMethod.VISIT, "서울시 강남구 2",
                "02-000-0000", null, "인터넷 취약계층만 현장 접수");

        assertThat(updateReception(announcement, List.of(online, visit))).isFalse();
        assertThat(updateReception(announcement, List.of(visit, online))).isTrue();
        assertThat(announcement.getReceptionPlaces()).containsExactly(visit, online);
        assertThat(updateReception(announcement, List.of(visit, online, movedVisit))).isTrue();
        assertThat(announcement.getReceptionPlaces()).extracting(ReceptionPlace::getAddress)
                .containsExactly("서울시 강남구 1", null, "서울시 강남구 2");
        assertThat(announcement.getReceptionPlaces().getFirst().getGuidance()).isEqualTo("인터넷 취약계층만 현장 접수");
    }

    @Test
    void LH_보강은_맡은_접수처를_LH_한_곳으로_바꾸고_LH가_접수처를_주지_않으면_저장된_목록을_둔다() {
        ReceptionPlace online = receptionPlace();
        ReceptionPlace visit = ReceptionPlace.create("견본주택", ReceptionMethod.VISIT, "서울시 1", null, null);
        ReceptionPlace lh = ReceptionPlace.create("LH 접수처", ReceptionMethod.VISIT, "충주시 2", "043-000-0000", null);
        Announcement announcement = announcement("기존 공고명", List.of(online, visit));

        assertThat(announcement.enrichFromLh("PAN-1", null, List.of())).isTrue();
        assertThat(announcement.getReceptionPlaces()).containsExactly(online, visit);
        assertThat(announcement.enrichFromLh("PAN-1", null, List.of(lh))).isTrue();
        assertThat(announcement.getReceptionPlaces()).containsExactly(lh);
        assertThat(announcement.isLhReceptionPlaceOwned()).isTrue();
        assertThat(announcement.enrichFromLh("PAN-1", null, null)).isFalse();
        assertThat(announcement.getReceptionPlaces()).containsExactly(lh);
    }

    @Test
    void 관리자가_고친_공고는_LH_접수처가_목록에_그대로_있으면_원천_변경으로_보지_않고_없거나_다르면_확인을_켠다() {
        ReceptionPlace lh = ReceptionPlace.create("LH 접수처", ReceptionMethod.VISIT, "충주시 2", "043-000-0000", null);
        Announcement announcement = announcement("기존 공고명", List.of(lh));
        // 관리자가 LH 접수처에 안내 문구만 붙여도 같은 접수처다.
        announcement.reviseByAdmin(announcement("기존 공고명", List.of(receptionPlace(),
                ReceptionPlace.create("LH 접수처", ReceptionMethod.VISIT, "충주시 2", "043-000-0000", null, "인터넷 취약계층만 현장 접수"))));

        assertThat(announcement.enrichFromLh("PAN-1", null, List.of(lh))).isFalse();
        assertThat(announcement.isSourceReviewRequired()).isFalse();
        assertThat(announcement.getReceptionPlaces()).hasSize(2);

        announcement.enrichFromLh("PAN-1", null,
                List.of(ReceptionPlace.create("LH 접수처", ReceptionMethod.VISIT, "충주시 3", "043-000-0000", null)));
        assertThat(announcement.isSourceReviewRequired()).isTrue();
        assertThat(announcement.getReceptionPlaces()).hasSize(2);
    }

    private boolean updateReception(Announcement announcement, List<ReceptionPlace> places) {
        return announcement.updateFromMyHome(null, null, "기존 공고명", AnnouncementPublicationType.ORIGINAL,
                RentalType.NATIONAL_RENTAL, RecruitmentType.NEW, AgencyCode.LH, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 14), LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/1", places);
    }

    @Test
    void 원천_공고_정보가_같으면_갱신하지_않는다() {
        Announcement announcement = announcement("기존 공고명");

        boolean updated = announcement.updateFromMyHome(
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
                List.of(receptionPlace())
        );

        assertThat(updated).isFalse();
    }

    private Announcement announcement(String name) {
        return announcement(name, List.of(receptionPlace()));
    }

    private Announcement announcement(String name, List<ReceptionPlace> places) {
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
                places
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
