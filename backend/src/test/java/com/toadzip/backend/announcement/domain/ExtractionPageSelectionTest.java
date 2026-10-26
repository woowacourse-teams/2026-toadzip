package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionPageSelectionTest {

    private static final String COVER = "여수미평1 영구임대주택 예비입주자 모집 (입주자모집공고일 : 2026. 10. 20.)";
    private static final String SUPPLY = "형별 공급계획 전용 주거 공용 26.37 298 26.37 13.50 39.87 31.32 239 31.32 12.60 43.92";
    private static final String RENT = "임대조건 임대보증금 월 임대료 26.37 2,520,000 126,000 2,394,000 50,160";
    private static final String QUALIFICATION = "3. 신청자격 입주자 모집공고일 현재 무주택세대구성원";
    private static final String SCHEDULE = "5. 공급일정 및 세부절차 신청기간 ‘26.11.10(화)~11.12(목) 09:00~18:00";
    private static final String CONTACT = "12. 문의처 신청문의 여수미평1관리소 (061-652-4568)";
    private static final String NOTICE = "8. 유의사항 이 주택의 임대차 계약기간은 2년이며";

    @Test
    void 공급표_임대조건표_일정_문의처가_있는_쪽과_첫_쪽을_고른다() {
        ExtractionPageSelection selection = ExtractionPageSelection.select(
                List.of(COVER, NOTICE, SUPPLY, NOTICE, RENT, NOTICE, SCHEDULE, NOTICE, CONTACT));

        assertThat(selection.pageNumbers()).containsExactly(1, 3, 4, 5, 6, 7, 9);
        assertThat(selection.wholeDocument()).isFalse();
    }

    @Test
    void 표가_다음_쪽으로_이어질_수_있어_공급표와_임대조건표의_다음_쪽을_함께_고른다() {
        ExtractionPageSelection selection = ExtractionPageSelection.select(
                List.of(COVER, SUPPLY + " " + RENT, QUALIFICATION, NOTICE));

        assertThat(selection.pageNumbers()).containsExactly(1, 2, 3);
    }

    @Test
    void 별지_양식_쪽은_키워드가_있어도_고르지_않는다() {
        ExtractionPageSelection selection = ExtractionPageSelection.select(
                List.of(COVER, SUPPLY, "[별지1] 공급신청서 " + RENT, RENT));

        assertThat(selection.pageNumbers()).containsExactly(1, 2, 4);
    }

    @Test
    void 쪽_번호_꼬리말이_앞에_추출된_별지_양식도_고르지_않는다() {
        ExtractionPageSelection selection = ExtractionPageSelection.select(
                List.of(COVER, SUPPLY, RENT, NOTICE, "- 5 - [별지2] 개인정보 수집 동의서 입주자선정방법"));

        assertThat(selection.pageNumbers()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void 천원_단위_임대조건표도_찾는다() {
        String rentInThousands = "금회모집 임대조건 임대보증금(천원) 월임대료 (원) 67,400 3,370 64,030 342,610";

        ExtractionPageSelection selection = ExtractionPageSelection.select(List.of(COVER, SUPPLY, rentInThousands));

        assertThat(selection.wholeDocument()).isFalse();
        assertThat(selection.pageNumbers()).containsExactly(1, 2, 3);
    }

    @Test
    void 공급표나_임대조건표를_찾지_못하면_전체_쪽을_쓴다() {
        ExtractionPageSelection selection = ExtractionPageSelection.select(List.of(COVER, SUPPLY, NOTICE));

        assertThat(selection.pageNumbers()).containsExactly(1, 2, 3);
        assertThat(selection.wholeDocument()).isTrue();
    }

    @Test
    void 본문에만_나오는_낱말은_값이_없으면_쪽을_고르지_않는다() {
        String prose = "당첨자 발표일이 동일한 주택 입주자선정제한 주거전용면적은 주거의 용도로만 쓰이는 면적";

        ExtractionPageSelection selection = ExtractionPageSelection.select(
                List.of(COVER, SUPPLY, RENT, NOTICE, prose, "문의처는 콜센터입니다"));

        assertThat(selection.pageNumbers()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void 글자_사이에_공백이_끼거나_굵은_글자가_겹쳐_추출돼도_찾는다() {
        String spacedRent = "임 대 보 증 금 월 임 대 료 2,520,000 2,394,000 50,160";
        String doubledSupply = "공공공공용용용용 26.37 31.32 55.83";

        ExtractionPageSelection selection = ExtractionPageSelection.select(List.of(COVER, NOTICE, doubledSupply,
                NOTICE, spacedRent, NOTICE));

        assertThat(selection.pageNumbers()).containsExactly(1, 3, 4, 5, 6);
    }
}
