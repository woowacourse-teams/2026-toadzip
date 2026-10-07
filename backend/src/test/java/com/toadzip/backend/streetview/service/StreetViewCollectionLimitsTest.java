package com.toadzip.backend.streetview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.streetview.domain.StreetViewEvent.Phase;
import com.toadzip.backend.streetview.domain.StreetViewEvent.ReasonCode;
import com.toadzip.backend.streetview.domain.StreetViewEvent.Type;
import com.toadzip.backend.streetview.domain.StreetViewEvent;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException.Reason;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException;
import com.toadzip.backend.streetview.service.StreetViewAttemptTracker.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StreetViewCollectionLimitsTest {
    @Test
    void 토큰은_초당_보충하고_상한을_넘겨_축적하지_않는다() {
        var clock = new MutableClock();
        var meters = new SimpleMeterRegistry();
        var admission = new StreetViewEventAdmission(clock, meters, 20, 2);
        admission.enter();
        admission.enter();
        rejected(admission::enter, Reason.RATE_LIMIT);
        clock.advance(Duration.ofMillis(49));
        rejected(admission::enter, Reason.RATE_LIMIT);
        clock.advance(Duration.ofMillis(1));
        admission.enter();
        clock.advance(Duration.ofDays(1));
        admission.enter();
        admission.enter();
        rejected(admission::enter, Reason.RATE_LIMIT);
        assertThat(meters.get("street_view.collection.rejected").tag("reason", "RATE_LIMIT").counter().count())
                .isEqualTo(3);
    }

    @Test
    void 종료가_먼저_와도_각각_한_번만_집계하고_시도_정체성이_다르면_거부한다() {
        var tracker = new StreetViewAttemptTracker(new MutableClock(), 10, Duration.ofMinutes(10));
        UUID id = UUID.randomUUID();
        var ready = event(id, 1, 0, Type.READY);
        assertThat(tracker.record(ready)).isEqualTo(Outcome.ACCEPTED);
        assertThat(tracker.record(event(id, 1, 0, Type.STARTED))).isEqualTo(Outcome.ACCEPTED);
        assertThat(tracker.record(ready)).isEqualTo(Outcome.DUPLICATE_TERMINAL);
        assertThat(tracker.record(event(id, 1, 0, Type.STARTED))).isEqualTo(Outcome.DUPLICATE_STARTED);
        assertThat(tracker.record(new StreetViewEvent(id, 1, 0, Type.FAILED, Phase.SDK,
                ReasonCode.SDK_AUTH_FAILED, 100))).isEqualTo(Outcome.CONFLICTING_TERMINAL);
        rejected(() -> tracker.record(event(id, 2, 0, Type.STARTED)), Reason.ATTEMPT_CONFLICT);
        rejected(() -> tracker.record(event(id, 1, 1, Type.STARTED)), Reason.ATTEMPT_CONFLICT);
    }

    @Test
    void 활성_시도를_퇴출하지_않고_TTL은_최초_수신부터_측정한다() {
        var clock = new MutableClock();
        var tracker = new StreetViewAttemptTracker(clock, 1, Duration.ofMinutes(10));
        UUID id = UUID.randomUUID();
        tracker.record(event(id, 1, 0, Type.STARTED));
        rejected(() -> tracker.record(event(UUID.randomUUID(), 1, 0, Type.STARTED)), Reason.CAPACITY);
        clock.advance(Duration.ofMinutes(9));
        assertThat(tracker.record(event(id, 1, 0, Type.STARTED))).isEqualTo(Outcome.DUPLICATE_STARTED);
        clock.advance(Duration.ofMinutes(1));
        assertThat(tracker.record(event(UUID.randomUUID(), 1, 0, Type.STARTED))).isEqualTo(Outcome.ACCEPTED);
        rejected(() -> tracker.record(event(id, 1, 0, Type.STARTED)), Reason.CAPACITY);
    }

    @Test
    void 동시_중복_시도는_한_번만_등록한다() throws Exception {
        var tracker = new StreetViewAttemptTracker(new MutableClock(), 1, Duration.ofMinutes(10));
        var event = event(UUID.randomUUID(), 1, 0, Type.READY);
        var executor = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Outcome>>();
        try {
            for (int index = 0; index < 32; index++) {
                results.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return tracker.record(event);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var result : results) {
                if (result.get(5, TimeUnit.SECONDS) == Outcome.ACCEPTED) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private StreetViewEvent event(UUID id, long complexId, long version, Type type) {
        if (type == Type.STARTED) {
            return new StreetViewEvent(id, complexId, version, type, Phase.DOCUMENT, null, 0);
        }
        return new StreetViewEvent(id, complexId, version, type, Phase.PANORAMA, null, 100);
    }

    private void rejected(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StreetViewCollectionException.class,
                exception -> assertThat(exception.getReason()).isEqualTo(reason));
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong milliseconds = new AtomicLong(1_000_000);

        void advance(Duration duration) {
            milliseconds.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(milliseconds.get());
        }
    }
}
