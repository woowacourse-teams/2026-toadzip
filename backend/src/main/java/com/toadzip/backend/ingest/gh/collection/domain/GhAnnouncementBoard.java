package com.toadzip.backend.ingest.gh.collection.domain;

/** GH주택청약센터의 청약공고 게시판. 임대상가 게시판은 수집하지 않는다. */
public enum GhAnnouncementBoard {
    RENTAL_HOUSE("sr7150", "임대주택"),
    PURCHASE_RENTAL("sr7155", "매입임대");

    public static final String SITE_URL = "https://apply.gh.or.kr";
    private static final String FILE_URL = "https://apply-cdn.gh.or.kr";

    private final String code;
    private final String displayName;

    GhAnnouncementBoard(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public String listUrl() {
        return SITE_URL + "/sb/sr/" + code + "/selectPbancRentHouseList.do";
    }

    public String listUrl(int page) {
        return listUrl() + "?pageIndex=" + page;
    }

    public String detailUrl(String pbancNo, String kindCode) {
        return SITE_URL + "/sb/sr/" + code + "/selectPbancDetailView.do?pbancNo=" + pbancNo
                + "&pbancKndCd=" + kindCode + "&previewYn=N";
    }

    public String housingTypeDetailUrl() {
        return SITE_URL + "/sb/sr/" + code + "/selectPblancHouseTyDetail.do";
    }

    /** 공고문 다운로드 URL 앞부분. 쿼리의 공고 번호까지 맞아야 이 게시판의 첨부다. */
    public String fileDownloadPrefix(String pbancNo) {
        return FILE_URL + "/sr/" + code + "/selectFileDown.do?pbancNo=" + pbancNo + "&";
    }
}
