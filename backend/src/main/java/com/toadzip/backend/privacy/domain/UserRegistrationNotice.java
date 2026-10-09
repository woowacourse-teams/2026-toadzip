package com.toadzip.backend.privacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "privacy_registration_notices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRegistrationNotice {

    @Id
    private Long userId;

    @Column(nullable = false, length = 100)
    private String policyVersion;

    @Column(nullable = false)
    private Instant recordedAt;

    private Instant purgeAfter;
}
