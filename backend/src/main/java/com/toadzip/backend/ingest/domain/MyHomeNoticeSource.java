package com.toadzip.backend.ingest.domain;

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

import com.toadzip.backend.ingest.dto.MyHomeNoticeSourceItem;

@Getter
@Entity
@Table(name = "myhome_notice_source")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeNoticeSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 500)
    private String sourceKey;

    private Instant collectedAt;

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

    private MyHomeNoticeSource(int sourceOrder, MyHomeNoticeSourceItem item) {
        this.sourceOrder = sourceOrder;
        sourceKey = sourceKeyOf(item);
        replaceWith(item);
    }

    public static MyHomeNoticeSource from(int sourceOrder, MyHomeNoticeSourceItem item) {
        return new MyHomeNoticeSource(sourceOrder, item);
    }

    public static String sourceKeyOf(MyHomeNoticeSourceItem item) {
        return keyPart(item.pblancId()) + keyPart(item.houseSn());
    }

    public void replaceWith(MyHomeNoticeSourceItem item) {
        pblancId = trim(item.pblancId());
        houseSn = item.houseSn();
        sttusNm = trim(item.sttusNm());
        pblancNm = trim(item.pblancNm());
        suplyInsttNm = trim(item.suplyInsttNm());
        houseTyNm = trim(item.houseTyNm());
        suplyTyNm = trim(item.suplyTyNm());
        beforePblancId = trim(item.beforePblancId());
        rcritPblancDe = trim(item.rcritPblancDe());
        przwnerPresnatnDe = trim(item.przwnerPresnatnDe());
        beginDe = trim(item.beginDe());
        endDe = trim(item.endDe());
        refrnc = trim(item.refrnc());
        url = trim(item.url());
        pcUrl = trim(item.pcUrl());
        mobileUrl = trim(item.mobileUrl());
        hsmpNm = trim(item.hsmpNm());
        brtcNm = trim(item.brtcNm());
        signguNm = trim(item.signguNm());
        fullAdres = trim(item.fullAdres());
        rnCodeNm = trim(item.rnCodeNm());
        refrnLegaldongNm = trim(item.refrnLegaldongNm());
        pnu = trim(item.pnu());
        heatMthdNm = trim(item.heatMthdNm());
        totHshldCo = trim(item.totHshldCo());
        sumSuplyCo = item.sumSuplyCo();
        rentGtn = item.rentGtn();
        enty = item.enty();
        surlus = item.surlus();
        mtRntchrg = item.mtRntchrg();
    }

    public void markCollectedAt(Instant collectedAt) {
        if (collectedAt == null) {
            throw new IllegalArgumentException("수집 시각은 필수입니다.");
        }
        this.collectedAt = collectedAt;
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
