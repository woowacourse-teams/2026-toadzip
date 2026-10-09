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
import org.hibernate.annotations.ColumnDefault;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String loginIdentifier;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(length = 254)
    private String email;

    @Column(length = 100)
    private String registrationPolicyVersion;

    @Column(nullable = false)
    @ColumnDefault("0")
    private long notificationSettingsRevision;

    private User(String loginIdentifier, LocalDateTime createdAt) {
        validateLoginIdentifier(loginIdentifier);
        validateCreatedAt(createdAt);
        this.loginIdentifier = loginIdentifier;
        this.createdAt = createdAt;
    }

    public static User create(String loginIdentifier, LocalDateTime createdAt) {
        return new User(loginIdentifier, createdAt);
    }

    public void updateEmail(String email) {
        if (email != null && email.length() <= 254 && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            this.email = email;
        }
    }

    public void recordRegistrationPolicy(String version) {
        if (version == null || version.isBlank() || version.length() > 100) {
            throw new IllegalArgumentException("회원 생성 시 적용한 정책 버전이 필요합니다.");
        }
        if (registrationPolicyVersion == null) {
            registrationPolicyVersion = version;
        }
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
