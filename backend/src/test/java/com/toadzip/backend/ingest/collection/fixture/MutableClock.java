package com.toadzip.backend.ingest.collection.fixture;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 통합 테스트에서 수집 시각을 앞으로 옮기는 시계다. */
public final class MutableClock extends Clock {

    private final Instant start;
    private final AtomicReference<Instant> instant;

    public MutableClock(Instant start) {
        this.start = start;
        this.instant = new AtomicReference<>(start);
    }

    public void reset() {
        instant.set(start);
    }

    public void advance(Duration duration) {
        instant.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
