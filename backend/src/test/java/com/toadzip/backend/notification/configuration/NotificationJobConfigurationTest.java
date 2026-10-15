package com.toadzip.backend.notification.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.notification.service.NotificationMaterializationService;
import com.toadzip.backend.notification.service.NotificationScheduleService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NotificationJobConfigurationTest {
    @Test
    void 기본값은_생성_스케줄러를_등록하지_않고_활성화_설정에만_등록한다() {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(NotificationJobConfiguration.class, NotificationMaterializerJob.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(NotificationScheduleService.class, () -> mock(NotificationScheduleService.class))
                .withBean(NotificationMaterializationService.class, () -> mock(NotificationMaterializationService.class));
        context.run(application -> assertThat(application).doesNotHaveBean(NotificationMaterializerJob.class));
        context.withPropertyValues("app.notification.materializer-enabled=true").run(application -> {
            assertThat(application).hasSingleBean(NotificationMaterializerJob.class);
            assertThat(application).hasBean("notificationMaterializerScheduler");
        });
    }

    @Test
    void 한번_실행에서_최대_20페이지만_처리한다() {
        var service = mock(NotificationMaterializationService.class);
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        when(service.materializeNextPage(now, 200)).thenReturn(200);
        new NotificationMaterializerJob(service, Clock.fixed(now, ZoneOffset.UTC),
                mock(NotificationScheduleService.class), new NotificationProperties(false, true, false, null, null))
                .materialize();
        verify(service, times(20)).materializeNextPage(now, 200);
    }
    @Test
    void 일정도_발행_활성화_설정에만_현재_시각으로_발행한다() {
        var schedules = mock(NotificationScheduleService.class);
        var materializer = mock(NotificationMaterializationService.class);
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        new NotificationMaterializerJob(materializer, clock, schedules,
                new NotificationProperties(false, true, false, null, null)).publishSchedules();
        verifyNoInteractions(schedules);
        new NotificationMaterializerJob(materializer, clock, schedules,
                new NotificationProperties(true, true, false, now.minusSeconds(1), null)).publishSchedules();
        verify(schedules).publishDue(now);
    }
}
