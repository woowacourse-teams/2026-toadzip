package com.toadzip.backend.admin.service;

import com.toadzip.backend.admin.exception.AdminLoginAttemptLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AdminLoginAttemptLimiter {

    private static final int MAXIMUM_FAILURE_COUNT = 5;

    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);

    private static final Duration BLOCK_DURATION = Duration.ofMinutes(10);

    private final Clock clock;

    private final Map<LoginAttemptKey, LoginAttemptState> attemptStates = new HashMap<>();

    public AdminLoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    AdminLoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized void registerAttempt(String remoteAddress, String loginIdentifier) {
        Instant now = clock.instant();
        removeExpiredStates(now);
        LoginAttemptKey key = LoginAttemptKey.from(remoteAddress, loginIdentifier);
        LoginAttemptState state = attemptStates.computeIfAbsent(key, ignored -> new LoginAttemptState());
        state.registerAttempt(now);
    }

    public synchronized void reset(String remoteAddress, String loginIdentifier) {
        LoginAttemptKey key = LoginAttemptKey.from(remoteAddress, loginIdentifier);
        attemptStates.remove(key);
    }

    private void removeExpiredStates(Instant now) {
        attemptStates.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private record LoginAttemptKey(String remoteAddress, String loginIdentifier) {

        private static LoginAttemptKey from(String remoteAddress, String loginIdentifier) {
            return new LoginAttemptKey(
                    normalizeRemoteAddress(remoteAddress),
                    normalizeLoginIdentifier(loginIdentifier)
            );
        }

        private static String normalizeRemoteAddress(String remoteAddress) {
            if (remoteAddress == null || remoteAddress.isBlank()) {
                return "unknown";
            }
            return remoteAddress.strip();
        }

        private static String normalizeLoginIdentifier(String loginIdentifier) {
            return loginIdentifier.strip().toLowerCase(Locale.ROOT);
        }
    }

    private static class LoginAttemptState {

        private final List<Instant> failedAttempts = new ArrayList<>();

        private Instant blockedUntil;

        private void registerAttempt(Instant now) {
            if (isBlocked(now)) {
                throw new AdminLoginAttemptLimitExceededException();
            }
            removeAttemptsOutsideWindow(now);
            if (failedAttempts.size() >= MAXIMUM_FAILURE_COUNT) {
                blockedUntil = now.plus(BLOCK_DURATION);
                throw new AdminLoginAttemptLimitExceededException();
            }
            failedAttempts.add(now);
        }

        private boolean isExpired(Instant now) {
            if (blockedUntil != null) {
                return !now.isBefore(blockedUntil);
            }
            removeAttemptsOutsideWindow(now);
            return failedAttempts.isEmpty();
        }

        private boolean isBlocked(Instant now) {
            return blockedUntil != null && now.isBefore(blockedUntil);
        }

        private void removeAttemptsOutsideWindow(Instant now) {
            Instant windowStart = now.minus(FAILURE_WINDOW);
            failedAttempts.removeIf(attempt -> !attempt.isAfter(windowStart));
        }
    }
}
