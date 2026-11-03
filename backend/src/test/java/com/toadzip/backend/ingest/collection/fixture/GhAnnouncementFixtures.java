package com.toadzip.backend.ingest.collection.fixture;

import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementBoard;

/** GH주택청약센터 목록·상세 응답에서 수집이 읽는 구조만 남긴 HTML. */
public final class GhAnnouncementFixtures {

    private GhAnnouncementFixtures() {
    }

    public record Row(String pbancNo, String supplyType, String title, String postedDate) {
    }

    public static String list(GhAnnouncementBoard board, int page, int total, Row... rows) {
        StringBuilder body = new StringBuilder();
        for (Row row : rows) {
            body.append("""
                    <tr>
                        <td>1</td>
                        <td>%s</td>
                        <td><a href="#a" class="text_cut" data-previewYn="N" data-pbancNo="%s" data-pbancKndCd="01"
                            data-bizTyNm="%s">%s</a></td>
                        <td>남양주시
                        <td><img src="/images/sub/pdf.png" alt="pdf파일"></td>
                        <td>%s</td>
                        <td>2026-10-20
                        <td>접수마감
                        <td><button type="button" class="btn_normal">확인</button></td>
                        <td>100</td>
                    </tr>
                    """.formatted(row.supplyType(), row.pbancNo(), row.supplyType(), row.title(), row.postedDate()));
        }
        if (rows.length == 0) {
            body.append("<tr><td colspan=\"10\" class=\"list_none\">자료가 없습니다.</td></tr>");
        }
        return """
                <html><body>
                <div class="board_list">
                    <p class="total_txt">총게시물 : <b class="color_blue">%d</b>&nbsp; 페이지 : <b class="color_blue">%d</b>/%d</p>
                    <table class="board_tbl"><tbody>%s</tbody></table>
                </div>
                <script>
                function fn_sel_page(pageIndex) {
                    $('#pageIndex').val(pageIndex);
                    $net.submit("bbsSearchFrm", "/sb/sr/%s/selectPbancRentHouseList.do");
                }
                </script>
                </body></html>
                """.formatted(total, page, Math.max(1, (total + 9) / 10), body, board.code());
    }

    public static String detail(GhAnnouncementBoard board, Row row) {
        String postedDate = row.postedDate().replace('-', '.');
        return """
                <html><body><nav>메뉴</nav>
                <div class="sub_textbox mt30">
                    <h5 class="subcont_title">상세정보</h5>
                    <table><caption>청약공고 상세정보</caption><tbody>
                        <tr><th scope="row">공고명</th><td colspan="3" class="txt_l"><b>%s</b></td></tr>
                        <tr><th scope="row">공고상태</th><td class="txt_l">접수마감</td>
                            <th scope="row">유형</th><td class="txt_l">%s</td></tr>
                        <tr><th scope="row">공고일</th><td colspan="3" class="txt_l">%s</td></tr>
                        <tr><th scope="row">공고문</th><td colspan="3" class="txt_l">
                            <p><a href="https://apply-cdn.gh.or.kr/sr/%s/selectFileDown.do?pbancNo=%s&atchFileSn=1604944&atchFileDtlSn=12&mode=1&v=1">
                                모집공고문.hwp (821760 Byte) <img src="/images/sub/hwp.png" alt="hwp 파일"></a></p>
                            <p><a href="https://apply-cdn.gh.or.kr/sr/%s/selectFileDown.do?pbancNo=%s&atchFileSn=1604944&atchFileDtlSn=13&mode=1&v=1">
                                모집공고문.pdf (1207812 Byte) <img src="/images/sub/pdf.png" alt="pdf 파일"></a></p>
                        </td></tr>
                    </tbody></table>
                    <h5 class="subcont_title">공급정보</h5>
                    <table class="mt20"><caption>사업지구별 공급정보</caption><tbody>
                        <tr><th scope="row">지구명</th><td class="txt_l">다산 센트럴파크6단지</td>
                            <th scope="row">소재지</th><td class="txt_l"><span>경기도 남양주시 다산동 6107</span></td></tr>
                        <tr><th scope="row">모집호수</th><td class="txt_l">280</td></tr>
                    </tbody></table>
                    <div class="caption_tbl"><button type="button" class="down_btn">다운로드</button></div>
                    <h5 class="subcont_title">공급일정</h5>
                    <ul class="bul_list"><li><span><b>온라인접수기간 : </b>2026.07.21 10:00 ~ 2026.07.24 17:00</span></li></ul>
                    <div class="bg_box"><span>첫 줄<br/>둘째 줄</span></div>
                    <script>noise()</script>
                </div>
                <footer>푸터</footer>
                <script>
                $('#btn_submit').on('click', function() {
                    $net.href("/sb/sr/%s/selectPbancRentHouseList.do");
                });
                </script>
                </body></html>
                """.formatted(row.title(), row.supplyType(), postedDate,
                board.code(), row.pbancNo(), board.code(), row.pbancNo(), board.code());
    }
}
