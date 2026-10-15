package com.toadzip.backend.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class NewAnnouncementNotificationPolicyTest {
    private final NewAnnouncementNotificationPolicy policy = new NewAnnouncementNotificationPolicy();
    private final Instant activation = Instant.parse("2026-10-12T15:00:00Z");

    @Test
    void 활성화_당일의_신규_원공고만_후보가_된다() {
        assertThat(policy.isCandidate(activation, LocalDate.of(2026, 10, 13),
                AnnouncementPublicationType.ORIGINAL, activation)).isTrue();
    }

    @Test
    void 과거게시물_기존공고_정정취소_날짜미상은_후보에서_제외한다() {
        assertThat(policy.isCandidate(activation.plusSeconds(10), LocalDate.of(2026, 10, 12),
                AnnouncementPublicationType.ORIGINAL, activation)).isFalse();
        assertThat(policy.isCandidate(activation.minusSeconds(1), LocalDate.of(2026, 10, 13),
                AnnouncementPublicationType.ORIGINAL, activation)).isFalse();
        for (var type : new AnnouncementPublicationType[] {
                AnnouncementPublicationType.CORRECTION, AnnouncementPublicationType.CANCELLATION}) {
            assertThat(policy.isCandidate(activation, LocalDate.of(2026, 10, 13), type, activation)).isFalse();
        }
        assertThat(policy.isCandidate(activation, null, AnnouncementPublicationType.ORIGINAL, activation)).isFalse();
        assertThat(policy.isCandidate(null, LocalDate.of(2026, 10, 13),
                AnnouncementPublicationType.ORIGINAL, activation)).isFalse();
    }
}
