package com.toadzip.backend.user.service;

import com.toadzip.backend.privacy.domain.PrivacyRetentionPolicy;
import com.toadzip.backend.user.repository.UserLifecycleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserDeletionService {

    private final UserLifecycleRepository repository;
    private final Clock clock;

    @Transactional
    public void delete(long userId) {
        Optional<String> identifier = repository.identifierOf(userId);
        if (identifier.isEmpty()) {
            return;
        }
        repository.lockIdentifier(identifier.get());
        if (!repository.lockUser(userId)) {
            return;
        }
        Instant now = clock.instant();
        repository.deletePersonalData(userId);
        repository.recordDeletion(identifier.get(), now,
                now.plus(PrivacyRetentionPolicy.ACCOUNT_DELETION_MARKER_RETENTION));
    }
}
