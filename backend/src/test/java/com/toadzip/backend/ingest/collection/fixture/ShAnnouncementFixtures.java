package com.toadzip.backend.ingest.collection.fixture;

public final class ShAnnouncementFixtures {

    private ShAnnouncementFixtures() {
    }

    public static String mainList() {
        return """
                <form id="mainform">
                    <input name="itm_seq_1" value="1"><input name="srchWord" value="모집">
                    <input name="multi_itm_seqsStr" value="2">
                    <div class="topTxt"><div class="mentcount">총 <strong>2</strong> 건 [1/1페이지]</div></div>
                </form>
                <div class="topTxt"><p>우편번호 검색: 총 <strong>529</strong> 건 [1/53페이지]</p></div>
                <div id="listTb"><table><tbody>
                    <tr><td>2</td><td class="txtL"><a onclick="getDetailView('100')">주택 입주자 모집</a></td>
                        <td>공급부</td><td>2026-10-08</td><td>0</td></tr>
                    <tr><td>1</td><td class="txtL"><a onclick="getDetailView('200')">다른 입주자 모집</a></td>
                        <td>공급부</td><td>2026-10-07</td><td>0</td></tr>
                </tbody></table></div>
                """;
    }

    public static String detail() {
        return """
                <input name="multi_itm_seq" value="0">
                <input name="multi_itm_seqs" value="2">
                <div class="detailTable gs0401Table firgs0401Table"><table>
                    <thead><tr><th>주택 입주자 모집</th></tr></thead>
                    <tbody>
                        <tr><td><ul><li><strong>등록일 : </strong>2026-10-08</li></ul></td></tr>
                        <tr><td><a class="btnAttach" onclick="existFile('0')">공고문.pdf</a></td></tr>
                        <tr><td><a class="btnAttach" onclick="existFile('1')">신청서.zip</a></td></tr>
                        <tr><td class="cont"><p>입주자 모집 안내</p><p>신청 안내<br>서류를 제출하세요.</p></td></tr>
                    </tbody>
                </table></div>
                <ul class="personInfo"><li><span>담당부서</span>공급부</li></ul>
                <script>initParam.downList = [
                    {"brdId":"GS0401","seq":"100","fileSeq":"2","fileSize":"100",
                     "oriFileNm":"공고문.pdf","fileTp":"A"},
                    {"brdId":"GS0401","seq":"100","fileSeq":"7","fileSize":"200",
                     "oriFileNm":"신청서.zip","fileTp":"A"}
                ];</script>
                """;
    }
}
