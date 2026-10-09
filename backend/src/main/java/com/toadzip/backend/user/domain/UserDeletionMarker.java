package com.toadzip.backend.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_deletion_markers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDeletionMarker {

    @Id
    @Column(length = 64)
    private String loginIdentifierHash;

    @Column(nullable = false)
    private Instant deletedAt;

    @Column(nullable = false)
    private Instant expiresAt;
}
