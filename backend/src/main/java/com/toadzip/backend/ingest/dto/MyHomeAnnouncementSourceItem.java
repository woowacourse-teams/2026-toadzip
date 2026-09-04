package com.toadzip.backend.ingest.dto;

import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSourceData;

public record MyHomeAnnouncementSourceItem(
        String pblancId,
        Integer houseSn,
        String sttusNm,
        String pblancNm,
        String suplyInsttNm,
        String houseTyNm,
        String suplyTyNm,
        String beforePblancId,
        String rcritPblancDe,
        String przwnerPresnatnDe,
        String beginDe,
        String endDe,
        String refrnc,
        String url,
        String pcUrl,
        String mobileUrl,
        String hsmpNm,
        String brtcNm,
        String signguNm,
        String fullAdres,
        String rnCodeNm,
        String refrnLegaldongNm,
        String pnu,
        String heatMthdNm,
        String totHshldCo,
        Integer sumSuplyCo,
        Long rentGtn,
        Long enty,
        Long surlus,
        Long mtRntchrg
) {

    public MyHomeAnnouncementSourceData toSourceData() {
        return new MyHomeAnnouncementSourceData(
                pblancId, houseSn, sttusNm, pblancNm, suplyInsttNm, houseTyNm, suplyTyNm,
                beforePblancId, rcritPblancDe, przwnerPresnatnDe, beginDe, endDe, refrnc, url,
                pcUrl, mobileUrl, hsmpNm, brtcNm, signguNm, fullAdres, rnCodeNm, refrnLegaldongNm,
                pnu, heatMthdNm, totHshldCo, sumSuplyCo, rentGtn, enty, surlus, mtRntchrg
        );
    }
}
