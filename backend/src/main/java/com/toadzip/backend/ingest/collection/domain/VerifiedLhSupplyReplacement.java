package com.toadzip.backend.ingest.collection.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "verified_lh_supply_replacements", indexes = @Index(
        name = "idx_verified_lh_supply_replacements_pending", columnList = "request_hash,proposed_fingerprint"
))
@NoArgsConstructor(access = PROTECTED)
public class VerifiedLhSupplyReplacement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 64)
    private String proposedFingerprint;

    @Column(nullable = false, length = 2000)
    private String evidenceUrl;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(nullable = false, length = 255)
    private String approvedBy;

    @Column(nullable = false)
    private Instant approvedAt;

    private Instant consumedAt;

    private Instant revokedAt;
}
