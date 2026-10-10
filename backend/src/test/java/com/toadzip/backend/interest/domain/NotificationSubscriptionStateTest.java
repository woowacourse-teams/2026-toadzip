package com.toadzip.backend.interest.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationSubscriptionStateTest {

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void 만료_시각과_같은_시각의_신청은_재활성화이며_취소는_상태변화가_아니다() {
        NotificationSubscriptionState state = new NotificationSubscriptionState(true, NOW);

        assertEquals(NotificationInterestOutcome.ACTIVATED, state.activationOutcome(NOW));
        assertEquals(NotificationInterestOutcome.UNCHANGED, state.cancellationOutcome(NOW));
    }

    @Test
    void 아직_유효한_신청은_중복완료가_아니며_취소하면_상태가_바뀐다() {
        NotificationSubscriptionState state = new NotificationSubscriptionState(true, NOW.plusSeconds(1));

        assertEquals(NotificationInterestOutcome.ALREADY_ACTIVE, state.activationOutcome(NOW));
        assertEquals(NotificationInterestOutcome.CANCELLED, state.cancellationOutcome(NOW));
    }

    @Test
    void 취소된_신청은_기한이_남아도_재활성화할_수_있다() {
        NotificationSubscriptionState state = new NotificationSubscriptionState(false, NOW.plusSeconds(1));

        assertEquals(NotificationInterestOutcome.ACTIVATED, state.activationOutcome(NOW));
        assertEquals(NotificationInterestOutcome.UNCHANGED, state.cancellationOutcome(NOW));
    }
}
