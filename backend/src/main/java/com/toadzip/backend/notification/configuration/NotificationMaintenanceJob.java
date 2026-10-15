package com.toadzip.backend.notification.configuration;

import com.toadzip.backend.notification.repository.NotificationMaintenanceRepository;
import com.toadzip.backend.notification.service.NotificationMaintenanceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.notification.maintenance-enabled", havingValue = "true")
public class NotificationMaintenanceJob {
    private final NotificationMaintenanceService service;
    private final NotificationMaintenanceRepository repository;
    private final Clock clock;
    private final Map<String, Map<String, AtomicLong>> values = Map.of(
            "outbox", values(), "delivery", values());

    public NotificationMaintenanceJob(NotificationMaintenanceService service, NotificationMaintenanceRepository repository,
            Clock clock, MeterRegistry registry) {
        this.service = service;
        this.repository = repository;
        this.clock = clock;
        values.forEach((queue, gauges) -> gauges.forEach((name, value) ->
                io.micrometer.core.instrument.Gauge.builder("notification.queue." + name, value, AtomicLong::doubleValue)
                        .tag("queue", queue).register(registry)));
    }

    @Scheduled(fixedDelay = 60000, scheduler = "notificationMaintenanceScheduler")
    public void observe() {
        for (var stats : repository.statistics(clock.instant())) {
            var gauges = values.get(stats.queue());
            gauges.get("pending").set(stats.pending());
            gauges.get("failed").set(stats.failed());
            gauges.get("delay.seconds").set(stats.delay());
        }
    }

    @Scheduled(fixedDelay = 600000, scheduler = "notificationMaintenanceScheduler")
    public void purge() {
        service.purge(clock.instant());
    }

    private static Map<String, AtomicLong> values() {
        return Map.of("pending", new AtomicLong(), "failed", new AtomicLong(), "delay.seconds", new AtomicLong());
    }
}
