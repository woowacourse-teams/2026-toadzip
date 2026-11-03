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

    /** 지구 두 곳(첫 지구는 주택형 두 개, 둘째 지구는 지구 접수처가 있음)과 공고 단위 접수처·안내가 있는 상세. */
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
                    <ul class="tab_box tab_box2 mb10">
                        <li id="li1" class="active pbancTab"><a href="javascript:tab_move('suply',1)">다산 센트럴파크6단지</a></li>
                        <li id="li2" class=" pbancTab"><a href="javascript:tab_move('suply',2)">안양관양 경기행복주택</a></li>
                    </ul>
                    <div id="tab1">
                        <table class="mt20"><caption>사업지구별 공급정보</caption><tbody>
                            <tr><th scope="row">지구명</th><td class="txt_l">다산 센트럴파크6단지</td>
                                <th scope="row">소재지</th><td class="txt_l"><span>경기도 남양주시 다산동 6107 </span></td></tr>
                            <tr><th scope="row">전용면적(m²)</th><td class="txt_l">33.71 ~ 46.5</td>
                                <th scope="row">모집호수</th><td class="txt_l">280</td></tr>
                            <tr><th scope="row">난방방식</th><td class="txt_l"></td>
                                <th scope="row">입주예정월</th><td class="txt_l"> 2027.03 </td></tr>
                        </tbody></table>
                        <h5 class="subcont_title">단지 관련 이미지 정보</h5>
                        <div class="caption_tbl"><button type="button" class="down_btn">다운로드</button></div>
                        <h5 class="subcont_title">주택형 안내</h5>
                        <ul class="bul_list"><li>상세공급정보를 확인하시려면 표에서 주택형을 클릭해주세요.</li></ul>
                        <div class="tbl_scroll"><table><caption>주택형 안내 테이블</caption><tbody class="tb_house">
                            <tr><th scope="col">주택형</th><th scope="col">전용면적(m²)</th><th scope="col">금회공급 세대수</th>
                                <th scope="col">임대보증금(원)</th><th scope="col">월임대료(원)</th><th scope="col">인터넷청약</th></tr>
                            <tr class="tb_house_con"><td><button type="button" data-bizCd="2011002" data-rcritNmtm="8"
                                data-houseTyCd="33A">33A </button></td><td>33.71</td><td>120</td>
                                <td>공고문 확인</td><td>공고문 확인</td><td>접수마감</td></tr>
                            <tr class="tb_house_con"><td><button type="button" data-bizCd="2011002" data-rcritNmtm="8"
                                data-houseTyCd="36A">36A 청년</button></td><td>36.8</td><td>60</td>
                                <td>12,000,000</td><td>150,000</td><td>접수마감</td></tr>
                        </tbody></table></div>
                        <h5 class="subcont_title refer_title"><i class="xi-error-o"></i> 안내사항</h5>
                        <div class="bg_box"><span>자세한 사항은 공고문을 확인해 주시기 바랍니다.</span></div>
                        <h5 class="subcont_title">공급일정</h5>
                        <ul class="bul_list">
                            <li class="mt5"><span><b>온라인접수기간 : </b>2026.07.21 10:00 ~ 2026.07.24 17:00</span></li>
                            <li class="mt5"><span><b>당첨자발표일 : </b>2026.08.19 17:00</span></li>
                        </ul>
                    </div>
                    <div id="tab2" style="display:none">
                        <table class="mt20"><caption>사업지구별 공급정보</caption><tbody>
                            <tr><th scope="row">지구명</th><td class="txt_l">안양관양 경기행복주택</td>
                                <th scope="row">소재지</th><td class="txt_l">경기도 안양시 동안구 관양동 1498-21
                                    <button type="button" class="btn_normal">지도보기</button></td></tr>
                            <tr><th scope="row">전용면적(m²)</th><td class="txt_l">36.39 ~ 36.9</td>
                                <th scope="row">모집호수</th><td class="txt_l">18</td></tr>
                            <tr><th scope="row">난방방식</th><td class="txt_l">지역난방</td>
                                <th scope="row">입주예정월</th><td class="txt_l">별도안내</td></tr>
                        </tbody></table>
                        <h5 class="subcont_title">접수처 정보</h5>
                        <table class="view_tbl"><caption>접수처</caption><tbody class="tb_infofc">
                            <tr><th scope="row">소재지</th><td colspan="3" class="txt_l">관양 임대센터</td></tr>
                            <tr><th scope="row">전화번호</th><td class="txt_l">031-000-0000</td>
                                <th scope="row">접수처 운영기간</th><td class="txt_l">~</td></tr>
                        </tbody></table>
                    </div>
                    <h5 class="subcont_title">접수처 정보</h5>
                    <table class="view_tbl"><caption>접수처</caption><tbody class="tb_infofc">
                        <tr><th scope="row">소재지</th><td colspan="3" class="txt_l">다산 임대센터
                            <button type="button" class="btn_normal">지도보기</button></td></tr>
                        <tr><th scope="row">전화번호</th><td class="txt_l">031-510-7100</td>
                            <th scope="row">접수처 운영기간</th><td class="txt_l">2026.07.20~2026.07.22</td></tr>
                    </tbody></table>
                    <h5 class="subcont_title">기타사항</h5>
                    <div class="border_box bg_txt_box"><span>기타 사항은 공고문을 확인해 주시기 바랍니다.</span></div>
                    <h5 class="subcont_title refer_title"><i class="xi-error-o"></i> 안내사항</h5>
                    <div class="bg_box"><span>※ 첫 줄<br/>※ 둘째 줄</span></div>
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
