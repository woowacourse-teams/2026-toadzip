package com.toadzip.backend.ingest.sh.collection.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "sh_announcement_source")
@NoArgsConstructor(access = PROTECTED)
public class ShAnnouncementSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String sourceKey;

    @Column(nullable = false, length = 100)
    private String seq;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String department;

    @Column(nullable = false)
    private LocalDate registeredDate;

    @Column(nullable = false, columnDefinition = "text")
    private String originalUrl;

    @Column(nullable = false, columnDefinition = "text")
    private String listUrl;

    @Column(columnDefinition = "text")
    private String bodyText;

    @Column(columnDefinition = "text")
    private String attachments;

    @Column(nullable = false)
    private Instant collectedAt;

    public static ShAnnouncementSource from(ShAnnouncementSnapshot snapshot, Instant now) {
        ShAnnouncementSource source = new ShAnnouncementSource();
        source.sourceKey = snapshot.sourceKey();
        source.seq = snapshot.seq();
        source.updateFrom(snapshot, now);
        return source;
    }

    public void updateFrom(ShAnnouncementSnapshot snapshot, Instant now) {
        if (!sourceKey.equals(snapshot.sourceKey())) {
            throw new IllegalArgumentException("다른 SH 게시글 원천으로 변경할 수 없습니다.");
        }
        // 제목·등록일이 바뀐 새 판에서 못 읽은 부분은 옛 판 값을 남기지 않고 비워 다시 받게 한다.
        boolean revised = title != null
                && (!title.equals(snapshot.title()) || !registeredDate.equals(snapshot.registeredDate()));
        title = snapshot.title();
        department = snapshot.department();
        registeredDate = snapshot.registeredDate();
        originalUrl = snapshot.originalUrl();
        listUrl = snapshot.listUrl();
        if (revised || snapshot.bodyText() != null) {
            bodyText = snapshot.bodyText();
        }
        if (revised || snapshot.attachments() != null) {
            attachments = snapshot.attachments();
        }
        collectedAt = now;
    }
}
