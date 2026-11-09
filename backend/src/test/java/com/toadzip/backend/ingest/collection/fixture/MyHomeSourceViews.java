package com.toadzip.backend.ingest.collection.fixture;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import java.time.Instant;

/** 원천 행 Entity 없이 정제 테스트에 넘길 마이홈 원천 View를 만든다. 수명주기 값은 테스트가 직접 정한다. */
public final class MyHomeSourceViews {

    private MyHomeSourceViews() {
    }

    public static MyHomeAnnouncementSourceView announcement(int order, MyHomeAnnouncementSourceSnapshot snapshot) {
        return MyHomeAnnouncementSourceView.read(null, order, null, null, 0, true, snapshot);
    }

    public static MyHomeAnnouncementSourceView collectedAt(MyHomeAnnouncementSourceView source, Instant collectedAt) {
        return MyHomeAnnouncementSourceView.read(source.getId(), source.getSourceOrder(), collectedAt,
                source.getLastSeenRunId(), source.getConsecutiveMissCount(), source.isActive(), source.snapshot());
    }

    /** 같은 행이 바뀐 내용으로 다시 수집된 경우. id와 수명주기 값은 그대로 둔다. */
    public static MyHomeAnnouncementSourceView replaced(
            MyHomeAnnouncementSourceView source, MyHomeAnnouncementSourceSnapshot snapshot
    ) {
        return MyHomeAnnouncementSourceView.read(source.getId(), source.getSourceOrder(), source.getCollectedAt(),
                source.getLastSeenRunId(), source.getConsecutiveMissCount(), source.isActive(), snapshot);
    }

    /**
     * 같은 행의 원천 값이나 수명주기 값 일부를 바꿔 read 경로로 다시 만든다. 바꾸지 않은 값은 그대로 둔다.
     * 원천 키는 바뀐 공고 ID·주택 순번으로 다시 계산된다.
     */
    public static AnnouncementChange change(MyHomeAnnouncementSourceView source) {
        return new AnnouncementChange(source);
    }

    /** 이번 실행에서 관찰된 행. */
    public static MyHomeAnnouncementSourceView seen(MyHomeAnnouncementSourceView source, String runId, Instant seenAt) {
        return MyHomeAnnouncementSourceView.read(source.getId(), source.getSourceOrder(), seenAt, runId, 0, true,
                source.snapshot());
    }

    /** 연속 2회 수집에서 빠져 비활성이 된 행. */
    public static MyHomeAnnouncementSourceView inactive(MyHomeAnnouncementSourceView source) {
        return MyHomeAnnouncementSourceView.read(source.getId(), source.getSourceOrder(), source.getCollectedAt(),
                source.getLastSeenRunId(), 2, false, source.snapshot());
    }

    public static MyHomeComplexSourceView complex(MyHomeComplexSourceSnapshot snapshot) {
        return MyHomeComplexSourceView.read(null, null, snapshot);
    }

    public static MyHomeComplexSourceView complex(MyHomeComplexSourceSnapshot snapshot, Instant collectedAt) {
        return MyHomeComplexSourceView.read(null, collectedAt, snapshot);
    }

    public static MyHomeComplexSourceView collectedAt(MyHomeComplexSourceView source, Instant collectedAt) {
        return MyHomeComplexSourceView.read(source.getId(), collectedAt, source.snapshot());
    }

    /** 같은 행이 바뀐 내용으로 다시 수집된 경우. */
    public static MyHomeComplexSourceView replaced(MyHomeComplexSourceView source, MyHomeComplexSourceSnapshot snapshot) {
        return MyHomeComplexSourceView.read(source.getId(), source.getCollectedAt(), snapshot);
    }

    public static final class AnnouncementChange {

        private Long id;
        private int sourceOrder;
        private final Instant collectedAt;
        private String lastSeenRunId;
        private final int consecutiveMissCount;
        private final boolean active;
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

        private AnnouncementChange(MyHomeAnnouncementSourceView source) {
            id = source.getId();
            sourceOrder = source.getSourceOrder();
            collectedAt = source.getCollectedAt();
            lastSeenRunId = source.getLastSeenRunId();
            consecutiveMissCount = source.getConsecutiveMissCount();
            active = source.isActive();
            MyHomeAnnouncementSourceSnapshot snapshot = source.snapshot();
            pblancId = snapshot.pblancId();
            houseSn = snapshot.houseSn();
            sttusNm = snapshot.sttusNm();
            pblancNm = snapshot.pblancNm();
            suplyInsttNm = snapshot.suplyInsttNm();
            houseTyNm = snapshot.houseTyNm();
            suplyTyNm = snapshot.suplyTyNm();
            beforePblancId = snapshot.beforePblancId();
            rcritPblancDe = snapshot.rcritPblancDe();
            przwnerPresnatnDe = snapshot.przwnerPresnatnDe();
            beginDe = snapshot.beginDe();
            endDe = snapshot.endDe();
            refrnc = snapshot.refrnc();
            url = snapshot.url();
            pcUrl = snapshot.pcUrl();
            mobileUrl = snapshot.mobileUrl();
            hsmpNm = snapshot.hsmpNm();
            brtcNm = snapshot.brtcNm();
            signguNm = snapshot.signguNm();
            fullAdres = snapshot.fullAdres();
            rnCodeNm = snapshot.rnCodeNm();
            refrnLegaldongNm = snapshot.refrnLegaldongNm();
            pnu = snapshot.pnu();
            heatMthdNm = snapshot.heatMthdNm();
            totHshldCo = snapshot.totHshldCo();
            sumSuplyCo = snapshot.sumSuplyCo();
            rentGtn = snapshot.rentGtn();
            enty = snapshot.enty();
            surlus = snapshot.surlus();
            mtRntchrg = snapshot.mtRntchrg();
        }

        public AnnouncementChange id(Long id) {
            this.id = id;
            return this;
        }

        public AnnouncementChange sourceOrder(int sourceOrder) {
            this.sourceOrder = sourceOrder;
            return this;
        }

        public AnnouncementChange lastSeenRunId(String lastSeenRunId) {
            this.lastSeenRunId = lastSeenRunId;
            return this;
        }

        public AnnouncementChange houseSn(Integer houseSn) {
            this.houseSn = houseSn;
            return this;
        }

        public AnnouncementChange sttusNm(String sttusNm) {
            this.sttusNm = sttusNm;
            return this;
        }

        public AnnouncementChange pblancNm(String pblancNm) {
            this.pblancNm = pblancNm;
            return this;
        }

        public AnnouncementChange suplyInsttNm(String suplyInsttNm) {
            this.suplyInsttNm = suplyInsttNm;
            return this;
        }

        public AnnouncementChange houseTyNm(String houseTyNm) {
            this.houseTyNm = houseTyNm;
            return this;
        }

        public AnnouncementChange suplyTyNm(String suplyTyNm) {
            this.suplyTyNm = suplyTyNm;
            return this;
        }

        public AnnouncementChange beforePblancId(String beforePblancId) {
            this.beforePblancId = beforePblancId;
            return this;
        }

        public AnnouncementChange rcritPblancDe(String rcritPblancDe) {
            this.rcritPblancDe = rcritPblancDe;
            return this;
        }

        public AnnouncementChange przwnerPresnatnDe(String przwnerPresnatnDe) {
            this.przwnerPresnatnDe = przwnerPresnatnDe;
            return this;
        }

        public AnnouncementChange beginDe(String beginDe) {
            this.beginDe = beginDe;
            return this;
        }

        public AnnouncementChange endDe(String endDe) {
            this.endDe = endDe;
            return this;
        }

        public AnnouncementChange refrnc(String refrnc) {
            this.refrnc = refrnc;
            return this;
        }

        public AnnouncementChange url(String url) {
            this.url = url;
            return this;
        }

        public AnnouncementChange hsmpNm(String hsmpNm) {
            this.hsmpNm = hsmpNm;
            return this;
        }

        public AnnouncementChange pnu(String pnu) {
            this.pnu = pnu;
            return this;
        }

        public AnnouncementChange sumSuplyCo(Integer sumSuplyCo) {
            this.sumSuplyCo = sumSuplyCo;
            return this;
        }

        public MyHomeAnnouncementSourceView read() {
            return MyHomeAnnouncementSourceView.read(id, sourceOrder, collectedAt, lastSeenRunId,
                    consecutiveMissCount, active, new MyHomeAnnouncementSourceSnapshot(pblancId, houseSn, sttusNm, pblancNm, suplyInsttNm, houseTyNm, suplyTyNm, beforePblancId, rcritPblancDe, przwnerPresnatnDe, beginDe, endDe, refrnc, url, pcUrl, mobileUrl, hsmpNm, brtcNm, signguNm, fullAdres, rnCodeNm, refrnLegaldongNm, pnu, heatMthdNm, totHshldCo, sumSuplyCo, rentGtn, enty, surlus, mtRntchrg));
        }
    }
}
