package com.toadzip.backend.ingest.collection.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "myhome_announcement_source")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeAnnouncementSource {

    private static final int INACTIVE_AFTER_CONSECUTIVE_MISSES = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 500)
    private String sourceKey;

    private Instant collectedAt;

    @Column(length = 100)
    private String lastSeenRunId;

    @Column(nullable = false)
    private int consecutiveMissCount;

    @Column(nullable = false)
    private boolean active;

    private Integer sourceOrder;

    private String pblancId;
    private Integer houseSn;
    private String sttusNm;
    private String pblancNm;
    private String suplyInsttNm;
    private String houseTyNm;
    private String suplyTyNm;
    private String beforePblancId;
    private String rcritPblancDe;
    private String przwnerPresnatnDe;
    private String beginDe;
    private String endDe;

    @Column(length = 2000)
    private String refrnc;

    @Column(length = 2000)
    private String url;

    @Column(length = 2000)
    private String pcUrl;

    @Column(length = 2000)
    private String mobileUrl;

    private String hsmpNm;
    private String brtcNm;
    private String signguNm;

    @Column(length = 1000)
    private String fullAdres;

    private String rnCodeNm;
    private String refrnLegaldongNm;
    private String pnu;
    private String heatMthdNm;
    private String totHshldCo;
    private Integer sumSuplyCo;
    private Long rentGtn;
    private Long enty;
    private Long surlus;
    private Long mtRntchrg;

    private MyHomeAnnouncementSource(int sourceOrder, MyHomeAnnouncementSourceSnapshot snapshot) {
        this.sourceOrder = sourceOrder;
        consecutiveMissCount = 0;
        active = true;
        sourceKey = sourceKeyOf(snapshot);
        replaceWith(snapshot);
    }

    public static MyHomeAnnouncementSource from(int sourceOrder, MyHomeAnnouncementSourceSnapshot snapshot) {
        return new MyHomeAnnouncementSource(sourceOrder, snapshot);
    }

    public static String sourceKeyOf(MyHomeAnnouncementSourceSnapshot snapshot) {
        return keyPart(snapshot.pblancId()) + keyPart(snapshot.houseSn());
    }

    public void replaceWith(MyHomeAnnouncementSourceSnapshot snapshot) {
        pblancId = trim(snapshot.pblancId());
        houseSn = snapshot.houseSn();
        sttusNm = trim(snapshot.sttusNm());
        pblancNm = trim(snapshot.pblancNm());
        suplyInsttNm = trim(snapshot.suplyInsttNm());
        houseTyNm = trim(snapshot.houseTyNm());
        suplyTyNm = trim(snapshot.suplyTyNm());
        beforePblancId = trim(snapshot.beforePblancId());
        rcritPblancDe = trim(snapshot.rcritPblancDe());
        przwnerPresnatnDe = trim(snapshot.przwnerPresnatnDe());
        beginDe = trim(snapshot.beginDe());
        endDe = trim(snapshot.endDe());
        refrnc = trim(snapshot.refrnc());
        url = trim(snapshot.url());
        pcUrl = trim(snapshot.pcUrl());
        mobileUrl = trim(snapshot.mobileUrl());
        hsmpNm = trim(snapshot.hsmpNm());
        brtcNm = trim(snapshot.brtcNm());
        signguNm = trim(snapshot.signguNm());
        fullAdres = trim(snapshot.fullAdres());
        rnCodeNm = trim(snapshot.rnCodeNm());
        refrnLegaldongNm = trim(snapshot.refrnLegaldongNm());
        pnu = trim(snapshot.pnu());
        heatMthdNm = trim(snapshot.heatMthdNm());
        totHshldCo = trim(snapshot.totHshldCo());
        sumSuplyCo = snapshot.sumSuplyCo();
        rentGtn = snapshot.rentGtn();
        enty = snapshot.enty();
        surlus = snapshot.surlus();
        mtRntchrg = snapshot.mtRntchrg();
    }

    public void markCollectedAt(Instant collectedAt) {
        if (collectedAt == null) {
            throw new IllegalArgumentException("수집 시각은 필수입니다.");
        }
        this.collectedAt = collectedAt;
    }

    public void markSeen(String runId, Instant seenAt) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("수집 실행 ID는 필수입니다.");
        }
        if (seenAt == null) {
            throw new IllegalArgumentException("마지막 확인 시각은 필수입니다.");
        }
        lastSeenRunId = runId;
        consecutiveMissCount = 0;
        active = true;
        markCollectedAt(seenAt);
    }

    public void markMissed() {
        consecutiveMissCount++;
        if (consecutiveMissCount >= INACTIVE_AFTER_CONSECUTIVE_MISSES) {
            active = false;
        }
    }

    private static String keyPart(Object raw) {
        if (raw == null) {
            return "-1:";
        }
        String value = raw.toString().strip();
        return value.length() + ":" + value;
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
