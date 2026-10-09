package com.toadzip.backend.user.service;

import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import com.toadzip.backend.user.repository.SocialUserLockRepository;
import com.toadzip.backend.user.repository.UserRepository;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.privacy.repository.UserRegistrationNoticeRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SocialUserService {

    private final UserRepository userRepository;
    private final SocialUserLockRepository userLocks;
    private final UserRegistrationNoticeRepository registrationNotices;
    private final PrivacyNoticeCatalog notices;
    private final Clock clock;

    @Transactional
    public Long findOrCreate(String provider, String subject, String email, SocialAuthorizationContext authorization) {
        String identifier = identifier(provider, subject);
        userLocks.lockIdentifier(identifier);
        var existing = userRepository.findForUpdateByLoginIdentifier(identifier);
        Instant now = clock.instant();
        if (authorization == null || !authorization.isValidAt(now)) {
            throw new IllegalArgumentException("로그인 요청이 없거나 만료됐습니다.");
        }
        if (existing.isPresent()) {
            User user = existing.get();
            return updateEmail(user, email);
        }
        User user = User.create(identifier, LocalDateTime.now(clock));
        user.updateEmail(email);
        Long userId = userRepository.saveAndFlush(user).getId();
        notices.findOptional("PRIVACY_POLICY", authorization.policyVersion())
                .ifPresent(notice -> registrationNotices.record(userId, notice.version(), now));
        return userId;
    }

    public String emailOf(Long id) {
        return userRepository.findById(id).orElseThrow().getEmail();
    }

    private Long updateEmail(User user, String email) {
        user.updateEmail(email);
        userRepository.save(user);
        return user.getId();
    }

    private String identifier(String provider, String subject) {
        if ((!"google".equals(provider) && !"kakao".equals(provider))
                || subject == null || subject.isBlank() || subject.length() > 240) {
            throw new IllegalArgumentException("지원하지 않는 소셜 로그인 식별정보입니다.");
        }
        return provider + ":" + subject;
    }
}
