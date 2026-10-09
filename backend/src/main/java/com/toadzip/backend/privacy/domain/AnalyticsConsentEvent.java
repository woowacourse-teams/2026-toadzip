package com.toadzip.backend.privacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "privacy_analytics_consent_events", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"consent_id", "command_id"}),
        @UniqueConstraint(columnNames = {"consent_id", "revision"})}, indexes = {
        @Index(name = "privacy_analytics_consent_events_purge_idx", columnList = "purge_after")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsConsentEvent {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consent_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AnalyticsConsent consent;
    @Column(nullable = false)
    private UUID commandId;
    @Column(nullable = false, length = 64)
    private String requestFingerprint;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsentDecision previousDecision;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsentDecision decision;
    @Column(nullable = false)
    private long previousRevision;
    @Column(nullable = false)
    private long revision;
    @Column(nullable = false, length = 80)
    private String noticeVersion;
    @Column(nullable = false, length = 80)
    private String scopeVersion;
    @Column(length = 80)
    private String previousNoticeVersion;
    @Column(length = 80)
    private String previousScopeVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsentSource source;
    @Column(nullable = false)
    private Instant recordedAt;
    private Instant expiresAt;
    private Instant supersededAt;
    private Instant purgeAfter;
}
