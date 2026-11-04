package com.toadzip.backend.ingest.sh.refinement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.sh.refinement.dto.ShAnnouncementBodyValues;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ShAnnouncementBodyParserTest {

    private final ShAnnouncementBodyParser parser = new ShAnnouncementBodyParser();

    @Test
    void 행복주택_요약에서_인터넷_접수기간과_당첨자_발표일과_신규_재공급_합계를_읽는다() {
        var values = parser.parse("""
                ■ 공급호수: 신규공급 154호, 재공급 1,330호(예비입주자 모집 포함)
                ○ 단지 및 공급호수 등 자세한 사항은 공고문을 확인하여 주시기 바랍니다.(첨부1)
                ■ 접수일
                ○ 인터넷 접수: 2026. 9. 9.(수) 10:00 ~ 9. 11.(금) 17:00
                ○ 방문 접수: 2026. 9. 10.(목) ~ 9. 11.(금) 10:00 ~ 17:00
                ■ 서류심사대상자 발표: 2026. 9. 21.(월) 16시 이후
                ■ 당첨자발표: 2027.01.29.(금) 예정 (금융자산 조회가 지연될 경우 당첨자 발표일은 변경될 수 있습니다.)
                """);

        assertThat(values).isEqualTo(new ShAnnouncementBodyValues(LocalDate.of(2026, 9, 9),
                LocalDate.of(2026, 9, 11), LocalDate.of(2027, 1, 29), 1_484));
    }

    @Test
    void 청약_접수일과_괄호_안_내역은_빼고_한_개의_세대수를_읽는다() {
        var values = parser.parse("""
                ■ 공급호수 : 573세대(신규공급 466세대, 재공급 107세대)
                ■ 청약 접수일: 2026. 04. 10.(금) 10:00 ~ 2026. 04. 14.(화) 17:00
                ■ 당첨자발표: 2026. 08. 12.(수)
                """);

        assertThat(values).isEqualTo(new ShAnnouncementBodyValues(LocalDate.of(2026, 4, 10),
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 8, 12), 573));
    }

    @Test
    void 표로만_접수일정을_적은_국민임대는_접수기간을_비우고_발표일과_공급단지_세대수만_읽는다() {
        var values = parser.parse("""
                □ 금회 공급단지 : 총 1,973세대
                ○ 재공급 단지 : 공가입주자 409세대, 예비입주자 1,564세대
                접수일정
                -방문접수 기간 : 2026.09.01.(화) ~ 2026.09.03.(목) 10:00~17:00
                □ 당첨자 발표일 : 2027.02.15.(월) 16:00 이후
                """);

        assertThat(values).isEqualTo(new ShAnnouncementBodyValues(null, null, LocalDate.of(2027, 2, 15), 1_973));
    }

    @Test
    void 단지_수와_세대수가_함께_있으면_세대수만_읽고_재공급과_예비입주자가_섞이면_비운다() {
        assertThat(parser.parse("□ 공급호수 : 162개 단지 3,821세대(예비입주자 모집 포함)").totalSupplyCount())
                .isEqualTo(3_821);
        assertThat(parser.parse("□ 금회 공급단지 : 33개 단지, 재공급 360세대 및 예비입주자 797세대").totalSupplyCount())
                .isNull();
    }

    @Test
    void 우편접수와_날짜_없는_인터넷접수_제목은_접수기간으로_쓰지_않는다() {
        var values = parser.parse("""
                ■ 접수일
                ○ 인터넷접수
                - 우편접수 기간: 2026. 10. 2.(금) ~ 2026. 10. 6.(화) (10.6. 도착분까지 유효)
                ■ 당첨자발표: 2026. 12. 28.(월) 18:00 이후
                """);

        assertThat(values.applicationStartDate()).isNull();
        assertThat(values.applicationEndDate()).isNull();
        assertThat(values.winnerAnnouncementDate()).isEqualTo(LocalDate.of(2026, 12, 28));
    }

    @Test
    void 영구임대의_예비입주자_발표를_발표일로_읽는다() {
        assertThat(parser.parse("○ 예비입주자 발표 : 2026. 3. 19.(목)").winnerAnnouncementDate())
                .isEqualTo(LocalDate.of(2026, 3, 19));
    }

    @Test
    void 같은_항목이_다른_날짜로_두_번_나오면_비운다() {
        var values = parser.parse("""
                ○ 인터넷 접수: 2026. 9. 9.(수) 10:00 ~ 9. 11.(금) 17:00
                ○ 인터넷 접수: 2026. 9. 21.(월) 10:00 ~ 9. 22.(화) 17:00
                ■ 당첨자발표: 2027.01.29.(금)
                ■ 당첨자 발표: 2027.02.05.(금)
                """);

        assertThat(values).isEqualTo(new ShAnnouncementBodyValues(null, null, null, null));
    }

    @Test
    void 연도가_없는_종료일이_시작일보다_앞서면_다음_해로_본다() {
        var values = parser.parse("○ 인터넷 접수: 2026. 12. 30.(수) 10:00 ~ 1. 2.(토) 17:00");

        assertThat(values.applicationEndDate()).isEqualTo(LocalDate.of(2027, 1, 2));
    }

    @Test
    void 본문이_없으면_모두_비운다() {
        assertThat(parser.parse(null)).isEqualTo(new ShAnnouncementBodyValues(null, null, null, null));
    }
}
