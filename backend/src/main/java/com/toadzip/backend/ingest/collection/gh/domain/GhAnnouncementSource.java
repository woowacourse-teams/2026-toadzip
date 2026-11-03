package com.toadzip.backend.ingest.collection.gh.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "gh_announcement_source")
@NoArgsConstructor(access = PROTECTED)
public class GhAnnouncementSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String sourceKey;

    @Column(nullable = false, length = 100)
    private String pbancNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private GhAnnouncementBoard board;

    @Column(nullable = false, length = 10)
    private String kindCode;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String supplyType;

    @Column(nullable = false, columnDefinition = "text")
    private String region;

    @Column(nullable = false)
    private LocalDate postedDate;

    private LocalDate closingDate;

    @Column(columnDefinition = "text")
    private String status;

    @Column(nullable = false, columnDefinition = "text")
    private String originalUrl;

    @Column(nullable = false, columnDefinition = "text")
    private String listUrl;

    @Column(nullable = false, columnDefinition = "text")
    private String bodyText;

    @Column(nullable = false, columnDefinition = "text")
    private String attachments;

    @Column(nullable = false)
    private Instant collectedAt;

    public static GhAnnouncementSource from(GhAnnouncementSnapshot snapshot, Instant now) {
        GhAnnouncementSource source = new GhAnnouncementSource();
        source.sourceKey = snapshot.sourceKey();
        source.pbancNo = snapshot.pbancNo();
        source.updateFrom(snapshot, now);
        return source;
    }

    public void updateFrom(GhAnnouncementSnapshot snapshot, Instant now) {
        if (!sourceKey.equals(snapshot.sourceKey())) {
            throw new IllegalArgumentException("다른 GH 공고 원천으로 변경할 수 없습니다.");
        }
        board = snapshot.board();
        kindCode = snapshot.kindCode();
        title = snapshot.title();
        supplyType = snapshot.supplyType();
        region = snapshot.region();
        postedDate = snapshot.postedDate();
        closingDate = snapshot.closingDate();
        status = snapshot.status();
        originalUrl = snapshot.originalUrl();
        listUrl = snapshot.listUrl();
        bodyText = snapshot.bodyText();
        attachments = snapshot.attachments();
        collectedAt = now;
    }
}
