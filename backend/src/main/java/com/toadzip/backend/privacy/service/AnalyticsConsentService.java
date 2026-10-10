package com.toadzip.backend.privacy.service;

import com.toadzip.backend.privacy.domain.AnalyticsCollectionPolicy;
import com.toadzip.backend.privacy.domain.AnalyticsConsent;
import com.toadzip.backend.privacy.domain.ConsentAction;
import com.toadzip.backend.privacy.domain.ConsentCommand;
import com.toadzip.backend.privacy.domain.ConsentDecision;
import com.toadzip.backend.privacy.domain.ConsentReceipt;
import com.toadzip.backend.privacy.domain.ConsentSource;
import com.toadzip.backend.privacy.domain.PrivacyRetentionPolicy;
import com.toadzip.backend.privacy.dto.AnalyticsContextResponse;
import com.toadzip.backend.privacy.dto.PrivacyChoiceRequest;
import com.toadzip.backend.privacy.dto.PrivacyChoiceResponse;
import com.toadzip.backend.privacy.exception.PrivacyException;
import com.toadzip.backend.privacy.repository.AnalyticsConsentRepository;
import com.toadzip.backend.privacy.repository.ConsentEventRepository;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalyticsConsentService {

    private final AnalyticsConsentRepository consents;
    private final ConsentEventRepository events;
    private final PrivacyNoticeCatalog notices;
    private final PrivacyRetentionPolicy retention;
    private final AnalyticsCollectionPolicy collection;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Transactional(readOnly = true)
    public AnalyticsContextResponse context(Long userId, String guestToken) {
        Instant now = clock.instant();
        if (userId != null) {
            if (!consents.memberExists(userId)) {
                throw new PrivacyException("UNAUTHORIZED", "로그인이 필요합니다.");
            }
            return response(userId, consents.findMember(userId, false).orElse(null), now);
        }
        AnalyticsConsent consent = consents.findGuest(guestToken, false).orElse(null);
        if (consent != null && consent.isExpired(now) && consent.getDecision() == ConsentDecision.UNSET) {
            consent = null;
        }
        return response(null, consent, now);
    }

    @Transactional
    public PreparedGuest prepareGuest(String existingToken) {
        AnalyticsConsent existing = consents.findGuest(existingToken, true).orElse(null);
        Instant now = clock.instant();
        if (existing != null && !existing.isExpired(now)) {
            return new PreparedGuest(existingToken, response(null, existing, now));
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AnalyticsConsent consent = consents.createGuest(token, now,
                now.plus(PrivacyRetentionPolicy.GUEST_CONTEXT_PREPARATION_LIFETIME));
        return new PreparedGuest(token, response(null, consent, now));
    }

    @Transactional
    public PrivacyChoiceResponse chooseMember(long userId, PrivacyChoiceRequest request) {
        ConsentCommand command = command(request);
        if (!Long.toString(userId).equals(command.expectedUserId()) || command.contextId() != null) {
            throw new PrivacyException("PRIVACY_SUBJECT_CHANGED", "선택을 적용할 계정이 변경되었습니다.");
        }
        consents.lockMember(userId);
        Instant now = clock.instant();
        AnalyticsConsent consent = consents.findMember(userId, true)
                .orElseGet(() -> consents.createMember(userId, now));
        return choose(userId, consent, command, now);
    }

    @Transactional
    public PrivacyChoiceResponse chooseGuest(String token, PrivacyChoiceRequest request) {
        ConsentCommand command = command(request);
        AnalyticsConsent consent = consents.findGuest(token, true)
                .orElseThrow(() -> new PrivacyException("PRIVACY_CONTEXT_REQUIRED", "브라우저 선택을 다시 준비해 주세요."));
        Instant now = clock.instant();
        if (consent.isExpired(now)) {
            throw new PrivacyException("PRIVACY_CONTEXT_REQUIRED", "브라우저 선택을 다시 준비해 주세요.");
        }
        if (!consent.getId().equals(command.contextId()) || command.expectedUserId() != null) {
            throw new PrivacyException("PRIVACY_SUBJECT_CHANGED", "선택을 적용할 브라우저가 변경되었습니다.");
        }
        return choose(null, consent, command, now);
    }

    private PrivacyChoiceResponse choose(Long userId, AnalyticsConsent consent, ConsentCommand command, Instant now) {
        ConsentReceipt receipt = events.find(consent.getId(), command.commandId()).orElse(null);
        if (receipt != null) {
            if (!receipt.fingerprint().equals(command.fingerprint())) {
                throw new PrivacyException("PRIVACY_COMMAND_CONFLICT", "동일한 요청 식별자로 다른 선택을 보낼 수 없습니다.");
            }
            return choiceResponse(receipt, userId, consent, now);
        }
        command.verifyRevision(consent.getRevision());
        verifyNotice(command);
        AnalyticsConsent previous = copy(consent);
        consent.choose(command.action(), notices.currentVersion("ANALYTICS_NOTICE"),
                notices.requiredAnalyticsScope(), now, retention);
        events.supersede(consent.getId(), previous.getRevision(), now,
                now.plus(PrivacyRetentionPolicy.SUPERSEDED_CONSENT_HISTORY_RETENTION));
        consents.save(consent);
        events.record(previous, consent, command, retention.consentPurgeAfter(consent.getExpiresAt()));
        return choiceResponse(new ConsentReceipt(command.commandId(), command.fingerprint(), consent.getDecision(),
                consent.getRevision(), now), userId, consent, now);
    }

    private void verifyNotice(ConsentCommand command) {
        if (command.action() != ConsentAction.GRANT) {
            return;
        }
        if (!notices.isCurrentVersion("ANALYTICS_NOTICE", command.noticeVersion())
                || !notices.requiredAnalyticsScope().equals(command.scopeVersion())) {
            throw new PrivacyException("PRIVACY_NOTICE_CHANGED", "안내가 변경되었습니다. 최신 내용을 확인해 주세요.");
        }
    }

    private ConsentCommand command(PrivacyChoiceRequest request) {
        try {
            if (request.expectedRevision() == null || request.expectedRevision() < 0) {
                throw new IllegalArgumentException();
            }
            validateVersionLength(request.noticeVersion());
            validateVersionLength(request.scopeVersion());
            UUID contextId = null;
            if (request.contextId() != null) {
                contextId = parseUUID(request.contextId());
            }
            return new ConsentCommand(parseUUID(request.commandId()), request.expectedUserId(), contextId,
                    request.expectedRevision(), ConsentAction.valueOf(request.action()), request.noticeVersion(),
                    request.scopeVersion(), ConsentSource.valueOf(request.source()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new PrivacyException("INVALID_PRIVACY_CHOICE", "선택 요청값이 올바르지 않습니다.");
        }
    }

    private void validateVersionLength(String value) {
        if (value != null && (value.length() > 80 || value.isBlank())) {
            throw new IllegalArgumentException();
        }
    }

    private UUID parseUUID(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException();
        }
        return parsed;
    }

    private AnalyticsConsent copy(AnalyticsConsent consent) {
        return AnalyticsConsent.restore(consent.getId(), consent.getGuestTokenHash(), consent.getDecision(),
                consent.getNoticeVersion(), consent.getScopeVersion(), consent.getDecidedAt(), consent.getExpiresAt(),
                consent.getRevision(), consent.getCreatedAt(), consent.getUpdatedAt());
    }

    private PrivacyChoiceResponse choiceResponse(ConsentReceipt receipt, Long userId, AnalyticsConsent consent,
            Instant now) {
        return new PrivacyChoiceResponse(new PrivacyChoiceResponse.Receipt(receipt.commandId().toString(),
                receipt.decision().name(), receipt.revision(), receipt.recordedAt()), response(userId, consent, now));
    }

    private AnalyticsContextResponse response(Long userId, AnalyticsConsent consent, Instant now) {
        String kind = "GUEST";
        String user = null;
        if (userId != null) {
            kind = "MEMBER";
            user = userId.toString();
        }
        String contextId = null;
        var choice = new AnalyticsContextResponse.Consent("UNSET", "UNSET", 0, null, null, null, null);
        if (consent != null) {
            contextId = consent.getId().toString();
            choice = new AnalyticsContextResponse.Consent(consent.getDecision().name(),
                    consent.effectiveStatus(notices.requiredAnalyticsScope(), now).name(), consent.getRevision(),
                    consent.getNoticeVersion(), consent.getScopeVersion(), consent.getDecidedAt(),
                    consent.getExpiresAt());
        }
        return new AnalyticsContextResponse(new AnalyticsContextResponse.Subject(kind, user, contextId), choice,
                notices.currentVersion("ANALYTICS_NOTICE"), notices.requiredAnalyticsScope(),
                collection.isAllowed(consent, notices.requiredAnalyticsScope(), now), now, 60);
    }

    public record PreparedGuest(String token, AnalyticsContextResponse context) {
    }
}
