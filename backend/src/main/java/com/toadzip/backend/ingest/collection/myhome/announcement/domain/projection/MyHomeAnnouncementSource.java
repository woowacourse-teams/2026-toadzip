package com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection;

import static lombok.AccessLevel.PROTECTED;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = PROTECTED)
public class MyHomeAnnouncementSource {

    private static final int INACTIVE_AFTER_CONSECUTIVE_MISSES = 2;

    private Long id;

    private String sourceKey;

    private Instant collectedAt;

    private String lastSeenRunId;

    private int consecutiveMissCount;

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

    private String refrnc;

    private String url;

    private String pcUrl;

    private String mobileUrl;

    private String hsmpNm;
    private String brtcNm;
    private String signguNm;

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

    public static MyHomeAnnouncementSource read(Long id, int order, Instant collectedAt, String lastSeenRunId,
            int missCount, boolean active,
            com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot snapshot) {
        var normalized = new MyHomeAnnouncementSourceSnapshot(snapshot.pblancId(), snapshot.houseSn(),
                snapshot.sttusNm(), snapshot.pblancNm(), snapshot.suplyInsttNm(), snapshot.houseTyNm(),
                snapshot.suplyTyNm(), snapshot.beforePblancId(), snapshot.rcritPblancDe(), snapshot.przwnerPresnatnDe(),
                snapshot.beginDe(), snapshot.endDe(), snapshot.refrnc(), snapshot.url(), snapshot.pcUrl(),
                snapshot.mobileUrl(), snapshot.hsmpNm(), snapshot.brtcNm(), snapshot.signguNm(), snapshot.fullAdres(),
                snapshot.rnCodeNm(), snapshot.refrnLegaldongNm(), snapshot.pnu(), snapshot.heatMthdNm(),
                snapshot.totHshldCo(), snapshot.sumSuplyCo(), snapshot.rentGtn(), snapshot.enty(), snapshot.surlus(),
                snapshot.mtRntchrg());
        var source = from(order, normalized);
        source.id = id;
        source.collectedAt = collectedAt;
        source.lastSeenRunId = lastSeenRunId;
        source.consecutiveMissCount = missCount;
        source.active = active;
        return source;
    }

    public MyHomeAnnouncementSourceSnapshot snapshot() {
        return new MyHomeAnnouncementSourceSnapshot(pblancId, houseSn, sttusNm, pblancNm, suplyInsttNm, houseTyNm,
                suplyTyNm, beforePblancId, rcritPblancDe, przwnerPresnatnDe, beginDe, endDe, refrnc, url, pcUrl,
                mobileUrl, hsmpNm, brtcNm, signguNm, fullAdres, rnCodeNm, refrnLegaldongNm, pnu, heatMthdNm, totHshldCo,
                sumSuplyCo, rentGtn, enty, surlus, mtRntchrg);
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
