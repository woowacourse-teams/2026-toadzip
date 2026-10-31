package com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceRow;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.domain.SourceText;
import java.time.Instant;
import lombok.Getter;

/** 정제·보강이 읽는 마이홈 공고 원천 행 하나. 원천 행 Entity를 읽어 만든 뒤 바뀌지 않는다. */
@Getter
public class MyHomeAnnouncementSourceView {

    private final Long id;

    private final String sourceKey;

    private final Instant collectedAt;

    private final String lastSeenRunId;

    private final int consecutiveMissCount;

    private final boolean active;

    private final Integer sourceOrder;

    private final String pblancId;
    private final Integer houseSn;
    private final String sttusNm;
    private final String pblancNm;
    private final String suplyInsttNm;
    private final String houseTyNm;
    private final String suplyTyNm;
    private final String beforePblancId;
    private final String rcritPblancDe;
    private final String przwnerPresnatnDe;
    private final String beginDe;
    private final String endDe;

    private final String refrnc;

    private final String url;

    private final String pcUrl;

    private final String mobileUrl;

    private final String hsmpNm;
    private final String brtcNm;
    private final String signguNm;

    private final String fullAdres;

    private final String rnCodeNm;
    private final String refrnLegaldongNm;
    private final String pnu;
    private final String heatMthdNm;
    private final String totHshldCo;
    private final Integer sumSuplyCo;
    private final Long rentGtn;
    private final Long enty;
    private final Long surlus;
    private final Long mtRntchrg;

    private MyHomeAnnouncementSourceView(Long id, int sourceOrder, Instant collectedAt, String lastSeenRunId,
            int consecutiveMissCount, boolean active, MyHomeAnnouncementSourceSnapshot snapshot) {
        this.id = id;
        this.sourceOrder = sourceOrder;
        this.collectedAt = collectedAt;
        this.lastSeenRunId = lastSeenRunId;
        this.consecutiveMissCount = consecutiveMissCount;
        this.active = active;
        sourceKey = sourceKeyOf(snapshot);
        pblancId = SourceText.strippedOrNull(snapshot.pblancId());
        houseSn = snapshot.houseSn();
        sttusNm = SourceText.strippedOrNull(snapshot.sttusNm());
        pblancNm = SourceText.strippedOrNull(snapshot.pblancNm());
        suplyInsttNm = SourceText.strippedOrNull(snapshot.suplyInsttNm());
        houseTyNm = SourceText.strippedOrNull(snapshot.houseTyNm());
        suplyTyNm = SourceText.strippedOrNull(snapshot.suplyTyNm());
        beforePblancId = SourceText.strippedOrNull(snapshot.beforePblancId());
        rcritPblancDe = SourceText.strippedOrNull(snapshot.rcritPblancDe());
        przwnerPresnatnDe = SourceText.strippedOrNull(snapshot.przwnerPresnatnDe());
        beginDe = SourceText.strippedOrNull(snapshot.beginDe());
        endDe = SourceText.strippedOrNull(snapshot.endDe());
        refrnc = SourceText.strippedOrNull(snapshot.refrnc());
        url = SourceText.strippedOrNull(snapshot.url());
        pcUrl = SourceText.strippedOrNull(snapshot.pcUrl());
        mobileUrl = SourceText.strippedOrNull(snapshot.mobileUrl());
        hsmpNm = SourceText.strippedOrNull(snapshot.hsmpNm());
        brtcNm = SourceText.strippedOrNull(snapshot.brtcNm());
        signguNm = SourceText.strippedOrNull(snapshot.signguNm());
        fullAdres = SourceText.strippedOrNull(snapshot.fullAdres());
        rnCodeNm = SourceText.strippedOrNull(snapshot.rnCodeNm());
        refrnLegaldongNm = SourceText.strippedOrNull(snapshot.refrnLegaldongNm());
        pnu = SourceText.strippedOrNull(snapshot.pnu());
        heatMthdNm = SourceText.strippedOrNull(snapshot.heatMthdNm());
        totHshldCo = SourceText.strippedOrNull(snapshot.totHshldCo());
        sumSuplyCo = snapshot.sumSuplyCo();
        rentGtn = snapshot.rentGtn();
        enty = snapshot.enty();
        surlus = snapshot.surlus();
        mtRntchrg = snapshot.mtRntchrg();
    }

    public static MyHomeAnnouncementSourceView of(MyHomeAnnouncementSourceRow row) {
        return read(row.getId(), row.getSourceOrder(), row.getCollectedAt(), row.getLastSeenRunId(),
                row.getConsecutiveMissCount(), row.isActive(), row.snapshot());
    }

    public static MyHomeAnnouncementSourceView read(Long id, int sourceOrder, Instant collectedAt,
            String lastSeenRunId, int consecutiveMissCount, boolean active,
            MyHomeAnnouncementSourceSnapshot snapshot) {
        return new MyHomeAnnouncementSourceView(id, sourceOrder, collectedAt, lastSeenRunId, consecutiveMissCount,
                active, snapshot);
    }

    public static String sourceKeyOf(MyHomeAnnouncementSourceSnapshot snapshot) {
        return keyPart(snapshot.pblancId()) + keyPart(snapshot.houseSn());
    }

    public MyHomeAnnouncementSourceSnapshot snapshot() {
        return new MyHomeAnnouncementSourceSnapshot(pblancId, houseSn, sttusNm, pblancNm, suplyInsttNm, houseTyNm,
                suplyTyNm, beforePblancId, rcritPblancDe, przwnerPresnatnDe, beginDe, endDe, refrnc, url, pcUrl,
                mobileUrl, hsmpNm, brtcNm, signguNm, fullAdres, rnCodeNm, refrnLegaldongNm, pnu, heatMthdNm, totHshldCo,
                sumSuplyCo, rentGtn, enty, surlus, mtRntchrg);
    }

    private static String keyPart(Object raw) {
        if (raw == null) {
            return "-1:";
        }
        String value = raw.toString().strip();
        return value.length() + ":" + value;
    }
}
