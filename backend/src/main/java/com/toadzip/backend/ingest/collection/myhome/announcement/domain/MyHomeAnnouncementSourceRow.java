package com.toadzip.backend.ingest.collection.myhome.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "myhome_announcement_source_rows", uniqueConstraints = @UniqueConstraint(
        columnNames = {"source_id", "request_supply_type_code", "source_order"}
))
@NoArgsConstructor(access = PROTECTED)
public class MyHomeAnnouncementSourceRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private MyHomeAnnouncementSource source;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "collection_record_id", nullable = false)
    private SourceCollectionRecord collectionRecord;

    @Column(length = 2)
    private String requestSupplyTypeCode;

    private Instant collectedAt;

    @Column(nullable = false)
    private int sourceOrder;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int consecutiveMissCount;

    @Column(length = 100)
    private String lastSeenRunId;

    @Column(nullable = false, length = 100)
    private String pblancId;

    @Column(nullable = false)
    private Integer houseSn;

    @Column(columnDefinition = "text")
    private String sttusNm;

    @Column(columnDefinition = "text")
    private String pblancNm;

    @Column(columnDefinition = "text")
    private String suplyInsttNm;

    @Column(columnDefinition = "text")
    private String houseTyNm;

    @Column(columnDefinition = "text")
    private String suplyTyNm;

    @Column(columnDefinition = "text")
    private String beforePblancId;

    @Column(columnDefinition = "text")
    private String rcritPblancDe;

    @Column(columnDefinition = "text")
    private String przwnerPresnatnDe;

    @Column(columnDefinition = "text")
    private String beginDe;

    @Column(columnDefinition = "text")
    private String endDe;

    @Column(columnDefinition = "text")
    private String refrnc;

    @Column(columnDefinition = "text")
    private String url;

    @Column(columnDefinition = "text")
    private String pcUrl;

    @Column(columnDefinition = "text")
    private String mobileUrl;

    @Column(columnDefinition = "text")
    private String hsmpNm;

    @Column(columnDefinition = "text")
    private String brtcNm;

    @Column(columnDefinition = "text")
    private String signguNm;

    @Column(columnDefinition = "text")
    private String fullAdres;

    @Column(columnDefinition = "text")
    private String rnCodeNm;

    @Column(columnDefinition = "text")
    private String refrnLegaldongNm;

    @Column(columnDefinition = "text")
    private String pnu;

    @Column(columnDefinition = "text")
    private String heatMthdNm;

    @Column(columnDefinition = "text")
    private String totHshldCo;

    private Integer sumSuplyCo;

    private Long rentGtn;

    private Long enty;

    private Long surlus;

    private Long mtRntchrg;

    private MyHomeAnnouncementSourceRow(
            MyHomeAnnouncementSource source, String supplyTypeCode, Instant collectedAt,
            SourceCollectionRecord record, int sourceOrder, MyHomeAnnouncementSourceSnapshot snapshot
    ) {
        this.source = source;
        requestSupplyTypeCode = supplyTypeCode;
        this.collectedAt = collectedAt;
        collectionRecord = record;
        this.sourceOrder = sourceOrder;
        lastSeenRunId = record.getId().toString();
        if (record.getExecutionId() != null) {
            lastSeenRunId = record.getExecutionId().toString();
        }
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

    public static MyHomeAnnouncementSourceRow from(
            MyHomeAnnouncementSource source, String supplyTypeCode, Instant collectedAt,
            SourceCollectionRecord record, int sourceOrder, MyHomeAnnouncementSourceSnapshot snapshot
    ) {
        if (!source.getPblancId().equals(snapshot.pblancId().strip())) {
            throw new IllegalArgumentException("응답 행의 공고 식별자가 소속 원천과 다릅니다.");
        }
        if (record.getSource() != CollectionSource.MYHOME_ANNOUNCEMENT
                || record.getStatus() == CollectionStatus.FAILED
                || !supplyTypeCode.equals(record.getRequestParameters().get("suplyTy"))) {
            throw new IllegalArgumentException("응답 행의 조회 조건과 수집 기록이 일치하지 않습니다.");
        }
        return new MyHomeAnnouncementSourceRow(source, supplyTypeCode, collectedAt, record, sourceOrder, snapshot);
    }

    public void markMissed() {
        consecutiveMissCount++;
        if (consecutiveMissCount >= 2) {
            active = false;
        }
    }

    public MyHomeAnnouncementSourceSnapshot snapshot() {
        return new MyHomeAnnouncementSourceSnapshot(
                pblancId, houseSn, sttusNm, pblancNm, suplyInsttNm,
                houseTyNm, suplyTyNm, beforePblancId, rcritPblancDe, przwnerPresnatnDe,
                beginDe, endDe, refrnc, url, pcUrl,
                mobileUrl, hsmpNm, brtcNm, signguNm, fullAdres,
                rnCodeNm, refrnLegaldongNm, pnu, heatMthdNm, totHshldCo,
                sumSuplyCo, rentGtn, enty, surlus, mtRntchrg
        );
    }
}
