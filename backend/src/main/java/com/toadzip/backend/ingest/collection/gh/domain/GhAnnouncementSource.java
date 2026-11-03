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

    /** 공급정보 지구 JSON 배열. 지구마다 주택형·공급일정·안내사항(행복주택은 접수처까지)을 담는다. */
    @Column(columnDefinition = "text")
    private String districts;

    /** 공고 단위 접수처 JSON 객체. 없으면 빈 객체다. */
    @Column(columnDefinition = "text")
    private String reception;

    @Column(columnDefinition = "text")
    private String notice;

    @Column(columnDefinition = "text")
    private String otherNotes;

    @Column(columnDefinition = "text")
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

    /** 상세를 다시 받지 않은 공고도 목록에서 바뀌는 지역·마감일·공고 상태는 맞춘다. 수집 시각은 상세 기준이라 두지 않는다. */
    public void updateListValues(String region, LocalDate closingDate, String status) {
        this.region = region;
        this.closingDate = closingDate;
        this.status = status;
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
        if (snapshot.districts() != null) {
            districts = snapshot.districts();
        }
        if (snapshot.reception() != null) {
            reception = snapshot.reception();
        }
        if (snapshot.notice() != null) {
            notice = snapshot.notice();
        }
        if (snapshot.otherNotes() != null) {
            otherNotes = snapshot.otherNotes();
        }
        if (snapshot.attachments() != null) {
            attachments = snapshot.attachments();
        }
        collectedAt = now;
    }
}
