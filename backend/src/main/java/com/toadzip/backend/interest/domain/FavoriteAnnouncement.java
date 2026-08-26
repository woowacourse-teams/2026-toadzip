package com.toadzip.backend.interest.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.announcement.domain.Announcement;
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
@Table(name = "favorite_announcements")
@NoArgsConstructor(access = PROTECTED)
public class FavoriteAnnouncement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    private Announcement announcement;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private FavoriteAnnouncement(User user, Announcement announcement, LocalDateTime createdAt) {
        validateRequired(user, "유저");
        validateRequired(announcement, "공고");
        validateRequired(createdAt, "등록일시");
        this.user = user;
        this.announcement = announcement;
        this.createdAt = createdAt;
    }

    public static FavoriteAnnouncement create(User user, Announcement announcement, LocalDateTime createdAt) {
        return new FavoriteAnnouncement(user, announcement, createdAt);
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }
}
