package com.toadzip.backend.ingest.collection.myhome.announcement.domain;

public record MyHomeAnnouncementSourceSnapshot(
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

    public void validateIdentifiers() {
        if (pblancId == null || pblancId.isBlank() || pblancId.length() > 100) {
            throw new IllegalArgumentException("마이홈 공고 식별자는 필수이며 100자 이하여야 합니다.");
        }
        if (houseSn == null || houseSn < 0) {
            throw new IllegalArgumentException("마이홈 주택 순번은 필수이며 음수일 수 없습니다.");
        }
    }
}
