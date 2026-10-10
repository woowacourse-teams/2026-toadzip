package com.toadzip.backend.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.privacy.domain.PrivacyNotice;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.privacy.repository.UserRegistrationNoticeRepository;
import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.repository.SocialUserLockRepository;
import com.toadzip.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SocialUserServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    @Mock private UserRepository users;
    @Mock private SocialUserLockRepository locks;
    @Mock private UserRegistrationNoticeRepository registrations;
    @Mock private PrivacyNoticeCatalog notices;

    @Test
    void 현재_정책의_범위가_달라도_이전_안내를_전달한_신규_가입을_허용한다() {
        PrivacyNotice prior = new PrivacyNotice("PRIVACY_POLICY", "prior", "scope-1", NOW, "hash", "prior");
        PrivacyNotice current = new PrivacyNotice("PRIVACY_POLICY", "current", "scope-2", NOW, "hash", "current");
        lenient().when(notices.current("PRIVACY_POLICY")).thenReturn(current);
        lenient().when(notices.find("PRIVACY_POLICY", "prior")).thenReturn(prior);
        when(notices.findOptional("PRIVACY_POLICY", "prior")).thenReturn(Optional.of(prior));
        when(users.findForUpdateByLoginIdentifier("google:subject")).thenReturn(Optional.empty());
        User saved = mock(User.class);
        when(saved.getId()).thenReturn(1L);
        when(users.saveAndFlush(any(User.class))).thenReturn(saved);

        assertEquals(1L, service().findOrCreate("google", "subject", null,
                new SocialAuthorizationContext("prior", NOW)));
        verify(registrations).record(1L, "prior", NOW);
        verify(notices, never()).current(anyString());
    }

    @Test
    void 기존_회원_로그인은_정책_카탈로그나_가입_고지를_조회하지_않는다() {
        User existing = mock(User.class);
        when(existing.getId()).thenReturn(2L);
        when(users.findForUpdateByLoginIdentifier("google:existing")).thenReturn(Optional.of(existing));

        assertEquals(2L, service().findOrCreate("google", "existing", "member@example.com",
                new SocialAuthorizationContext("unavailable-version", NOW)));
        verify(existing).updateEmail("member@example.com");
        verifyNoInteractions(notices, registrations);
    }

    private SocialUserService service() {
        return new SocialUserService(users, locks, registrations, notices, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
