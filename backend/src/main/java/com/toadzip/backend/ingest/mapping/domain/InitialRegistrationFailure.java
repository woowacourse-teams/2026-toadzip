package com.toadzip.backend.ingest.mapping.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "initial_registration_failures")
@NoArgsConstructor(access = PROTECTED)
public class InitialRegistrationFailure {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String sourceComplexIdentifier;
    @Column(nullable = false, columnDefinition = "text")
    private String detail;
    @Column(nullable = false)
    private Instant failedAt;
    private String actor;
    private UUID executionId;

    public InitialRegistrationFailure(String identifier, String detail, Instant failedAt, String actor,
            UUID executionId) {
        sourceComplexIdentifier = identifier;
        this.detail = detail;
        this.failedAt = failedAt;
        this.actor = actor;
        this.executionId = executionId;
    }
}
