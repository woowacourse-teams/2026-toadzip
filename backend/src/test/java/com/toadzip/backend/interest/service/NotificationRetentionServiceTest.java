package com.toadzip.backend.interest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.privacy.repository.PrivacyNotificationRetentionRepository;
import com.toadzip.backend.privacy.service.PrivacyNotificationRetentionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class NotificationRetentionServiceTest {

    @Test
    void 청크별_실행과_실패_재시도는_개인정보없는_운영지표를_남긴다() {
        PrivacyNotificationRetentionRepository repository = mock(PrivacyNotificationRetentionRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        Instant now = Instant.parse("2026-10-09T00:00:00Z");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PrivacyNotificationRetentionService service = new PrivacyNotificationRetentionService(repository,
                Clock.fixed(now, ZoneOffset.UTC), manager, registry);
        when(repository.purgeChunk(any(), anyInt())).thenThrow(new IllegalStateException("simulated"));

        assertThrows(IllegalStateException.class, service::purgeExpiredData);
        verify(manager).rollback(any());
        assertEquals(1, registry.get("privacy.retention.consecutive.failures").gauge().value());
        assertEquals(0, registry.get("privacy.retention.last.success.seconds").gauge().value());

        doReturn(500, 2, 0).when(repository).purgeChunk(any(), anyInt());
        when(repository.backlog(now)).thenReturn(new PrivacyNotificationRetentionRepository.Backlog(3, 1801));
        service.purgeExpiredData();

        assertEquals(502, registry.get("privacy.retention.deleted.total").counter().count());
        assertEquals(0, registry.get("privacy.retention.consecutive.failures").gauge().value());
        assertEquals(now.getEpochSecond(), registry.get("privacy.retention.last.success.seconds").gauge().value());
        assertEquals(3, registry.get("privacy.retention.overdue.count").gauge().value());
        assertEquals(1801, registry.get("privacy.retention.oldest.overdue.seconds").gauge().value());
    }
}
