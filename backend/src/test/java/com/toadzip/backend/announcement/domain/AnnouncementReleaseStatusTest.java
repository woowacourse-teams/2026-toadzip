package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnnouncementReleaseStatusTest {

    @Test
    void 미확정_공고는_일정과_유형_없이_만들고_식별값은_요구한다() {
        Announcement unconfirmed = unconfirmed();

        assertThat(unconfirmed.getReleaseStatus()).isEqualTo(AnnouncementReleaseStatus.UNCONFIRMED);
        assertThat(unconfirmed.getApplicationStartDate()).isNull();
        assertThat(unconfirmed.getSupplyType()).isNull();
        assertThatThrownBy(() -> Announcement.createUnconfirmed("SH:1", " ", AgencyCode.SH,
                LocalDate.of(2026, 8, 1), "https://example.com/sh/1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Announcement.createUnconfirmed("SH:1", "공고", AgencyCode.SH, null,
                "https://example.com/sh/1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 관리자가_필수값을_채워도_미확정_공고는_그대로_두고_공개는_따로_한_번만_한다() {
        Announcement announcement = unconfirmed();

        announcement.reviseByAdmin(revision());
        assertThat(announcement.isPublished()).isFalse();
        assertThat(announcement.getApplicationEndDate()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(announcement.hasPublishRequirements()).isTrue();

        assertThat(announcement.publish()).isTrue();
        assertThat(announcement.isPublished()).isTrue();
        assertThat(announcement.publish()).isFalse();
    }

    @Test
    void 필수값이_빈_미확정_공고는_공개하지_못한다() {
        Announcement announcement = unconfirmed();

        assertThat(announcement.hasPublishRequirements()).isFalse();
        assertThatThrownBy(announcement::publish).isInstanceOf(IllegalStateException.class);
        assertThat(announcement.isPublished()).isFalse();
    }

    @Test
    void 공개된_공고의_관리자_보정은_공개_상태를_바꾸지_않는다() {
        Announcement published = revision();

        published.reviseByAdmin(revision());
        assertThat(published.isPublished()).isTrue();
        assertThat(published.publish()).isFalse();
    }

    private Announcement unconfirmed() {
        return Announcement.createUnconfirmed("SH:1", "SH 공고", AgencyCode.SH, LocalDate.of(2026, 8, 1),
                "https://example.com/sh/1");
    }

    private Announcement revision() {
        return Announcement.create("SH:1", null, null, "SH 공고", AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING, RecruitmentType.NEW, AgencyCode.SH, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 14), LocalDate.of(2026, 9, 1),
                "https://example.com/sh/1", null, 0L, null);
    }
}
