package com.toadzip.backend.user.service;

import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SocialUserService {

    private final UserRepository userRepository;
    private final Clock clock;

    public Long findOrCreate(String provider, String subject) {
        String identifier = identifier(provider, subject);
        return userRepository.findByLoginIdentifier(identifier)
                .map(User::getId)
                .orElseGet(() -> createOrFind(identifier));
    }

    private Long createOrFind(String identifier) {
        try {
            return userRepository.saveAndFlush(User.create(identifier, LocalDateTime.now(clock))).getId();
        } catch (DataIntegrityViolationException exception) {
            return userRepository.findByLoginIdentifier(identifier)
                    .map(User::getId)
                    .orElseThrow(() -> exception);
        }
    }

    private String identifier(String provider, String subject) {
        if ((!"google".equals(provider) && !"kakao".equals(provider))
                || subject == null || subject.isBlank() || subject.length() > 240) {
            throw new IllegalArgumentException("지원하지 않는 소셜 로그인 식별정보입니다.");
        }
        return provider + ":" + subject;
    }
}
