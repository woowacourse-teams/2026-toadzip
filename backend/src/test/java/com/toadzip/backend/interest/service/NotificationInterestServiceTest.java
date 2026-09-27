package com.toadzip.backend.interest.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.interest.domain.NotificationEventSource;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.repository.NotificationInterestRepository;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationInterestServiceTest {

    private final NotificationInterestRepository repository = mock(NotificationInterestRepository.class);
    private final HousingComplexRepository complexes = mock(HousingComplexRepository.class);
    private final AnnouncementRepository announcements = mock(AnnouncementRepository.class);
    private final NotificationInterestService service = new NotificationInterestService(
            repository, mock(RegionCodeResolver.class), complexes, announcements, Clock.systemUTC());

    @Test
    void 존재하는_단지와_공고의_수요를_저장한다() {
        when(complexes.existsById(1L)).thenReturn(true);
        when(announcements.existsById(2L)).thenReturn(true);

        service.record(request(NotificationEventSource.COMPLEX_DETAIL, NotificationTargetType.COMPLEX, "1"));
        service.record(request(NotificationEventSource.ANNOUNCEMENT_DETAIL, NotificationTargetType.ANNOUNCEMENT, "2"));

        verify(complexes).existsById(1L);
        verify(announcements).existsById(2L);
        verify(repository, org.mockito.Mockito.times(2)).record(any());
    }

    @Test
    void 숫자_범위를_넘는_대상은_저장하지_않는다() {
        assertThrows(InvalidNotificationInterestException.class, () -> service.record(
                request(NotificationEventSource.COMPLEX_DETAIL, NotificationTargetType.COMPLEX, "9999999999999999999")));
        verify(repository, never()).record(any());
    }

    private NotificationInterestRequest request(
            NotificationEventSource source, NotificationTargetType targetType, String targetId) {
        return new NotificationInterestRequest(UUID.randomUUID(), UUID.randomUUID(), NotificationEventType.CLICKED,
                source, targetType, targetId);
    }
}
