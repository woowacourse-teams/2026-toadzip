package com.toadzip.backend.user.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String loginIdentifier;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private User(String loginIdentifier, LocalDateTime createdAt) {
        validateLoginIdentifier(loginIdentifier);
        validateCreatedAt(createdAt);
        this.loginIdentifier = loginIdentifier;
        this.createdAt = createdAt;
    }

    public static User create(String loginIdentifier, LocalDateTime createdAt) {
        return new User(loginIdentifier, createdAt);
    }

    private void validateLoginIdentifier(String loginIdentifier) {
        if (loginIdentifier == null || loginIdentifier.isBlank()) {
            throw new IllegalArgumentException("로그인 식별정보는 필수다.");
        }
    }

    private void validateCreatedAt(LocalDateTime createdAt) {
        if (createdAt == null) {
            throw new IllegalArgumentException("생성일시는 필수다.");
        }
    }
}
