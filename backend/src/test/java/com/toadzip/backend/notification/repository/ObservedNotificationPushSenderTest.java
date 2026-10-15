package com.toadzip.backend.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.notification.domain.NotificationSendResult;
import com.toadzip.backend.notification.domain.NotificationSendResult.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ObservedNotificationPushSenderTest {
    @Test
    void 발송_결과와_소요시간을_개인정보_없는_태그로_기록한다() {
        var registry = new SimpleMeterRegistry();
        var sender = new ObservedNotificationPushSender(delivery ->
                new NotificationSendResult(Status.ACCEPTED, "provider-id", null, null), registry);
        sender.send(null);
        assertThat(registry.get("notification.push.result").tag("result", "ACCEPTED").counter().count()).isEqualTo(1);
        assertThat(registry.get("notification.push.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void 예외도_시간과_실패를_기록하고_호출자에게_전달한다() {
        var registry = new SimpleMeterRegistry();
        var sender = new ObservedNotificationPushSender(delivery -> { throw new IllegalStateException(); }, registry);
        assertThatThrownBy(() -> sender.send(null)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.get("notification.push.result").tag("result", "EXCEPTION").counter().count()).isEqualTo(1);
        assertThat(registry.get("notification.push.duration").timer().count()).isEqualTo(1);
    }
}
