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

@Getter
@Entity
@Table(name = "myhome_announcement_source")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeAnnouncementSource {

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

    private MyHomeAnnouncementSource(int sourceOrder, MyHomeAnnouncementSourceData data) {
        this.sourceOrder = sourceOrder;
        sourceKey = sourceKeyOf(data);
        replaceWith(data);
    }

    public static MyHomeAnnouncementSource from(int sourceOrder, MyHomeAnnouncementSourceData data) {
        return new MyHomeAnnouncementSource(sourceOrder, data);
    }

    public static String sourceKeyOf(MyHomeAnnouncementSourceData data) {
        return keyPart(data.pblancId()) + keyPart(data.houseSn());
    }

    public void replaceWith(MyHomeAnnouncementSourceData data) {
        pblancId = trim(data.pblancId());
        houseSn = data.houseSn();
        sttusNm = trim(data.sttusNm());
        pblancNm = trim(data.pblancNm());
        suplyInsttNm = trim(data.suplyInsttNm());
        houseTyNm = trim(data.houseTyNm());
        suplyTyNm = trim(data.suplyTyNm());
        beforePblancId = trim(data.beforePblancId());
        rcritPblancDe = trim(data.rcritPblancDe());
        przwnerPresnatnDe = trim(data.przwnerPresnatnDe());
        beginDe = trim(data.beginDe());
        endDe = trim(data.endDe());
        refrnc = trim(data.refrnc());
        url = trim(data.url());
        pcUrl = trim(data.pcUrl());
        mobileUrl = trim(data.mobileUrl());
        hsmpNm = trim(data.hsmpNm());
        brtcNm = trim(data.brtcNm());
        signguNm = trim(data.signguNm());
        fullAdres = trim(data.fullAdres());
        rnCodeNm = trim(data.rnCodeNm());
        refrnLegaldongNm = trim(data.refrnLegaldongNm());
        pnu = trim(data.pnu());
        heatMthdNm = trim(data.heatMthdNm());
        totHshldCo = trim(data.totHshldCo());
        sumSuplyCo = data.sumSuplyCo();
        rentGtn = data.rentGtn();
        enty = data.enty();
        surlus = data.surlus();
        mtRntchrg = data.mtRntchrg();
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
