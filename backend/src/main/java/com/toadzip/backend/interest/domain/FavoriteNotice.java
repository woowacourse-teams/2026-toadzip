package com.toadzip.backend.interest.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.notice.domain.Notice;
import com.toadzip.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "favorite_notices")
@NoArgsConstructor(access = PROTECTED)
public class FavoriteNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "notice_id")
    private Notice notice;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private FavoriteNotice(User user, Notice notice, LocalDateTime createdAt) {
        validateRequired(user, "유저");
        validateRequired(createdAt, "등록일시");
        this.user = user;
        this.notice = notice;
        this.createdAt = createdAt;
    }

    public static FavoriteNotice create(User user, Notice notice, LocalDateTime createdAt) {
        return new FavoriteNotice(user, notice, createdAt);
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }
}
