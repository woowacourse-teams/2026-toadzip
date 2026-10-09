package com.toadzip.backend.privacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "privacy_analytics_consents")
@Check(constraints = "(user_id IS NULL) <> (guest_token_hash IS NULL)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsConsent {

    @Id
    private UUID id;
    @Column(name = "user_id", unique = true)
    private Long userId;
    @Column(name = "guest_token_hash", unique = true, length = 64)
    private String guestTokenHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsentDecision decision;
    @Column(length = 80)
    private String noticeVersion;
    @Column(length = 80)
    private String scopeVersion;
    private Instant decidedAt;
    private Instant expiresAt;
    @Column(nullable = false)
    private long revision;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public static AnalyticsConsent restore(UUID id, String guestTokenHash, ConsentDecision decision,
            String noticeVersion, String scopeVersion, Instant decidedAt, Instant expiresAt,
            long revision, Instant createdAt, Instant updatedAt) {
        AnalyticsConsent consent = new AnalyticsConsent();
        consent.id = id;
        consent.guestTokenHash = guestTokenHash;
        consent.decision = decision;
        consent.noticeVersion = noticeVersion;
        consent.scopeVersion = scopeVersion;
        consent.decidedAt = decidedAt;
        consent.expiresAt = expiresAt;
        consent.revision = revision;
        consent.createdAt = createdAt;
        consent.updatedAt = updatedAt;
        return consent;
    }

    public boolean isGuest() {
        return guestTokenHash != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public ConsentStatus effectiveStatus(String requiredScopeVersion, Instant now) {
        if (decision == ConsentDecision.GRANTED && !requiredScopeVersion.equals(scopeVersion)) {
            return ConsentStatus.RECONSENT_REQUIRED;
        }
        if (isExpired(now)) {
            return ConsentStatus.EXPIRED;
        }
        return ConsentStatus.valueOf(decision.name());
    }

    public void choose(ConsentAction action, String currentNotice, String currentScope, Instant now,
            PrivacyRetentionPolicy retention) {
        decision = decision.choose(action);
        noticeVersion = currentNotice;
        scopeVersion = currentScope;
        decidedAt = now;
        updatedAt = now;
        expiresAt = retention.choiceExpiresAt(isGuest(), decision, now);
        revision++;
    }
}
