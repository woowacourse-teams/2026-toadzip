package com.toadzip.backend.ingest.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MyHomeAnnouncementUrlTest {

    private static final String DETAIL = "https://www.myhome.go.kr/hws/portal/sch/"
            + "selectRsdtRcritNtcDetailView.do";

    @ParameterizedTest
    @ValueSource(strings = {"?pblancId=21026", "?pageIndex=1&pblancId=21026&srchPblancNm=",
            "?%70blancId=%32%31%30%32%36"})
    void 상세_URL에서_단일_ID만_추출한다(String query) {
        assertThat(MyHomeAnnouncementUrl.parse(" " + DETAIL + query + " ").announcementIdentifier())
                .isEqualTo("21026");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?pageIndex=1", "?pblancId=", "?pblancId", "?pblancId=abc",
            "?pblancId=21026&pblancId=21027", "?pblancId=21026&%70blancId=21026",
            "?pblancId=21026%26pblancId%3D21027", "?pblancId=%GG"})
    void ID_누락과_중복과_잘못된_인코딩을_거부한다(String query) {
        assertThatThrownBy(() -> MyHomeAnnouncementUrl.parse(DETAIL + query))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://apply.lh.or.kr?pblancId=21026", "https://www.i-sh.co.kr?pblancId=21026",
            "https://www.myhome.go.kr.evil.com/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=21026",
            "https://www.myhome.go.kr@evil.com/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=21026",
            "https://evil@www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=21026",
            "https://www.myhome.go.kr:3100/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=21026",
            "ftp://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=21026",
            "https://www.myhome.go.kr/wrong?pblancId=21026", "/hws/portal/sch/detail?pblancId=21026",
            "not a url"})
    void 다른_기관과_위장_호스트와_상세_외_경로를_거부한다(String url) {
        assertThatThrownBy(() -> MyHomeAnnouncementUrl.parse(url)).isInstanceOf(IllegalArgumentException.class);
    }
}
