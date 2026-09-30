package com.toadzip.backend.interest.service;

import com.toadzip.backend.interest.repository.GuestCancellationRepository;
import com.toadzip.backend.interest.repository.GuestCancellationRepository.Challenge;
import com.toadzip.backend.interest.repository.GuestCancellationRepository.Request;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class GuestCancellationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final GuestCancellationRepository repository;
    private final Clock clock;

    @Transactional
    public void request(String email) {
        repository.request(normalize(email), clock.instant());
    }

    @Transactional(readOnly = true)
    public List<Request> pending() {
        return repository.pending();
    }

    @Transactional
    public IssuedCode issue(UUID id) {
        Request request = repository.findForUpdate(id);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request.codeExpiresAt() != null && request.codeExpiresAt().isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 유효한 코드가 있습니다.");
        }
        return createCode(id);
    }

    @Transactional
    public IssuedCode reissue(UUID id) {
        if (repository.findForUpdate(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return createCode(id);
    }

    private IssuedCode createCode(UUID id) {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = clock.instant().plus(Duration.ofHours(24));
        repository.issue(id, hash(code), expiresAt);
        return new IssuedCode(code, expiresAt);
    }

    @Transactional
    public void markSent(UUID id, String sender, String code) {
        Request request = repository.findForUpdate(id);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request.codeExpiresAt() == null || !request.codeExpiresAt().isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "발송할 유효한 코드가 없습니다.");
        }
        if (!repository.matchesCode(id, hash(code))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 코드가 재발급되었습니다.");
        }
        if (request.codeSentAt() != null) {
            return;
        }
        repository.markSent(id, sender, clock.instant());
    }

    @Transactional
    public boolean verifyAndCancel(String email, String code) {
        String normalized = normalize(email);
        Challenge challenge = repository.findChallengeForUpdate(normalized);
        if (challenge == null || challenge.hash() == null || challenge.expiresAt() == null
                || !challenge.expiresAt().isAfter(clock.instant()) || challenge.failedAttempts() >= 5) {
            return false;
        }
        byte[] actual = hash(code).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = challenge.hash().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(actual, expected)) {
            repository.failedAttempt(challenge.id());
            return false;
        }
        repository.cancelGuestSubscriptions(normalized);
        return true;
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedCode(String code, Instant expiresAt) {
    }
}
