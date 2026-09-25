package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementDetailResponseParser;
import com.toadzip.backend.announcement.domain.ScheduleType;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LhAnnouncementEnrichmentMapperTest {

    private static final String PAN_ID = "100";

    private final LhAnnouncementEnrichmentMapper mapper = new LhAnnouncementEnrichmentMapper();

    @Test
    void 국민임대_실응답에서_접수와_서류대상자_발표와_당첨자_발표를_구분한다() throws Exception {
        var result = mapDetailFixture("062");

        assertThat(result.schedules()).extracting(LhScheduleData::type, LhScheduleData::name,
                        LhScheduleData::startAt, LhScheduleData::endAt)
                .contains(
                        tuple(ScheduleType.APPLICATION, "접수", LocalDateTime.of(2026, 9, 29, 0, 0),
                                LocalDateTime.of(2026, 9, 29, 0, 0)),
                        tuple(ScheduleType.ETC, "서류제출 대상자 발표", LocalDateTime.of(2026, 10, 13, 0, 0),
                                LocalDateTime.of(2026, 10, 13, 0, 0)),
                        tuple(ScheduleType.WINNER_ANNOUNCEMENT, "당첨자 발표", LocalDateTime.of(2027, 1, 15, 0, 0),
                                LocalDateTime.of(2027, 1, 15, 0, 0))
                );
        assertThat(result.schedules()).filteredOn(row -> row.type() == ScheduleType.WINNER_ANNOUNCEMENT)
                .hasSize(1);
    }

    @Test
    void 공공임대_실응답의_당첨자_발표와_당첨자_서류제출_일정을_보존한다() throws Exception {
        var result = mapDetailFixture("060");

        assertThat(result.schedules()).extracting(LhScheduleData::type, LhScheduleData::startAt, LhScheduleData::endAt)
                .containsExactly(
                        tuple(ScheduleType.APPLICATION, LocalDateTime.of(2026, 9, 29, 9, 0),
                                LocalDateTime.of(2026, 9, 30, 16, 0)),
                        tuple(ScheduleType.WINNER_ANNOUNCEMENT, LocalDateTime.of(2026, 10, 6, 0, 0),
                                LocalDateTime.of(2026, 10, 6, 0, 0)),
                        tuple(ScheduleType.DOCUMENT_SUBMISSION, LocalDateTime.of(2026, 10, 7, 0, 0),
                                LocalDateTime.of(2026, 10, 14, 0, 0))
                );
    }

    private LhAnnouncementEnrichmentData mapDetailFixture(String type) throws Exception {
        try (var input = getClass().getResourceAsStream("/ingest/lh/detail-" + type + "-excerpt.json")) {
            var details = new LhAnnouncementDetailResponseParser().parse(PAN_ID,
                    JsonMapper.builder().build().readTree(input));
            return mapper.map(PAN_ID, details, List.of());
        }
    }

    @Test
    void 날짜만_있는_접수_기간을_시작일과_종료일로_보강한다() {
        var details = new LhAnnouncementDetailResponseParser().parse(PAN_ID,
                JsonMapper.builder().build().readTree("""
                        [{"dsSplScdl":[{"ACP_DTTM":"2026.09.14 ~ 2026.09.15"}]}]
                        """));

        var result = mapper.map(PAN_ID, details, List.of());

        assertThat(result.schedules()).singleElement().satisfies(schedule -> {
            assertThat(schedule.startAt()).isEqualTo(LocalDateTime.of(2026, 9, 14, 0, 0));
            assertThat(schedule.endAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0));
        });
    }

    @Test
    void 별도_안내인_접수_기간은_일정을_만들지_않는다() {
        var details = new LhAnnouncementDetailResponseParser().parse(PAN_ID,
                JsonMapper.builder().build().readTree("""
                        [{"dsSplScdl":[{"ACP_DTTM":"별도 안내"}]}]
                        """));

        assertThat(mapper.map(PAN_ID, details, List.of()).schedules()).isEmpty();
    }

    @Test
    void 다단지_공고는_공급행의_단지에_해당하는_입주예정월을_매핑한다() {
        List<LhAnnouncementDetailSource> details = List.of(
                complexDetail(0, "동삼2", "202612"),
                complexDetail(1, "청운3", "202703")
        );
        List<LhAnnouncementSupplySource> supplies = List.of(
                supply(0, "동삼2", "46A"),
                supply(1, "청운3", "59B")
        );

        LhAnnouncementEnrichmentData result = mapper.map(PAN_ID, details, supplies);

        assertThat(result.supplies())
                .extracting(LhSupplyData::complexName, LhSupplyData::expectedMoveInMonth)
                .containsExactly(
                        tuple("동삼2", YearMonth.of(2026, 12)),
                        tuple("청운3", YearMonth.of(2027, 3))
                );
    }

    @Test
    void 다단지_공고는_단지명_접미어가_달라도_해당_입주예정월을_매핑한다() {
        List<LhAnnouncementDetailSource> details = List.of(
                complexDetail(0, "중동한라1", "202612"),
                complexDetail(1, "중동덕유1", "202703")
        );
        List<LhAnnouncementSupplySource> supplies = List.of(
                supply(0, "중동한라1 영구임대주택", "46A"),
                supply(1, "중동덕유1 영구임대주택", "59B")
        );

        LhAnnouncementEnrichmentData result = mapper.map(PAN_ID, details, supplies);

        assertThat(result.supplies())
                .extracting(LhSupplyData::complexName, LhSupplyData::expectedMoveInMonth)
                .containsExactly(
                        tuple("중동한라1 영구임대주택", YearMonth.of(2026, 12)),
                        tuple("중동덕유1 영구임대주택", YearMonth.of(2027, 3))
                );
    }

    @Test
    void 다단지_공고에서_단지가_불일치하면_입주예정월을_매핑하지_않는다() {
        List<LhAnnouncementDetailSource> details = List.of(
                complexDetail(0, "동삼2", "202612"),
                complexDetail(1, "청운3", "202703")
        );

        LhAnnouncementEnrichmentData result = mapper.map(
                PAN_ID, details, List.of(supply(0, "매칭되지 않는 단지", "46A"))
        );

        assertThat(result.supplies()).singleElement()
                .extracting(LhSupplyData::expectedMoveInMonth)
                .isNull();
    }

    @Test
    void 빈_단지명은_단일_후보에_입주예정월을_임의로_연결하지_않는다() {
        LhAnnouncementEnrichmentData result = mapper.map(
                PAN_ID,
                List.of(complexDetail(0, "", "202612")),
                List.of(supply(0, "동삼2", "46A"))
        );

        assertThat(result.supplies()).singleElement()
                .extracting(LhSupplyData::expectedMoveInMonth)
                .isNull();
    }

    @Test
    void LH가_2999년으로_표시한_미정_입주예정월은_매핑하지_않는다() {
        LhAnnouncementEnrichmentData result = mapper.map(
                PAN_ID,
                List.of(complexDetail(0, "동삼2", "2999.01")),
                List.of(supply(0, "동삼2", "46A"))
        );

        assertThat(result.supplies()).singleElement()
                .extracting(LhSupplyData::expectedMoveInMonth)
                .isNull();
    }

    @Test
    void 쉼표가_올바른_세대수와_금액은_단일_숫자로_매핑한다() {
        LhAnnouncementSupplySource supply = supply(
                0, "동삼2", "46A", "1,000", "20", "10,000,000", "200,000"
        );

        LhSupplyData result = mapper.map(PAN_ID, List.of(), List.of(supply)).supplies().getFirst();

        assertThat(result.totalHouseholdCount()).isEqualTo(1_000);
        assertThat(result.supplyHouseholdCount()).isEqualTo(20);
        assertThat(result.rentalDeposit()).isEqualByComparingTo("10000000");
        assertThat(result.monthlyRent()).isEqualByComparingTo("200000");
    }

    @Test
    void 앞뒤_공백과_Long_최대값은_정상_숫자로_매핑한다() {
        LhAnnouncementSupplySource supply = supply(
                0, "동삼2", "46A", " 9999 ", " 20 ", "9223372036854775807", " 200,000 "
        );

        LhSupplyData result = mapper.map(PAN_ID, List.of(), List.of(supply)).supplies().getFirst();

        assertThat(result.totalHouseholdCount()).isEqualTo(9_999);
        assertThat(result.supplyHouseholdCount()).isEqualTo(20);
        assertThat(result.rentalDeposit()).isEqualByComparingTo("9223372036854775807");
        assertThat(result.monthlyRent()).isEqualByComparingTo("200000");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-10", "10~20", "10세대", "1,00", "1 000", "+10", "10.5",
            "9999세대", "9999~10000", "9999.5", "9999,99"
    })
    void 세대수가_허용된_단일_정수_형식이_아니면_실패한다(String invalidValue) {
        LhAnnouncementSupplySource supply = supply(
                0, "동삼2", "46A", invalidValue, "20", "10,000,000", "200,000"
        );

        assertThatThrownBy(() -> mapper.map(PAN_ID, List.of(), List.of(supply)))
                .isInstanceOf(LhAnnouncementEnrichmentRejectedException.class)
                .hasMessage("전체 세대수 형식이 올바르지 않습니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-10", "10~20만원", "10만원", "1,00", "1 000", "+10", "10.5",
            "9999만원", "9999~10000", "9999.5", "9999,99", "9223372036854775808"
    })
    void 금액이_허용된_단일_정수_형식이_아니면_실패한다(String invalidValue) {
        LhAnnouncementSupplySource supply = supply(
                0, "동삼2", "46A", "100", "20", invalidValue, "200,000"
        );

        assertThatThrownBy(() -> mapper.map(PAN_ID, List.of(), List.of(supply)))
                .isInstanceOf(LhAnnouncementEnrichmentRejectedException.class)
                .hasMessage("임대보증금 형식이 올바르지 않습니다.");
    }

    @Test
    void 공급_세대수의_형식이_잘못되면_실패한다() {
        LhAnnouncementSupplySource supply = supply(
                0, "동삼2", "46A", "100", "10~20", "10,000,000", "200,000"
        );

        assertThatThrownBy(() -> mapper.map(PAN_ID, List.of(), List.of(supply)))
                .isInstanceOf(LhAnnouncementEnrichmentRejectedException.class)
                .hasMessage("공급 세대수 형식이 올바르지 않습니다.");
    }

    @Test
    void 월_임대료의_형식이_잘못되면_실패한다() {
        LhAnnouncementSupplySource supply = supply(
                0, "동삼2", "46A", "100", "20", "10,000,000", "10~20만원"
        );

        assertThatThrownBy(() -> mapper.map(PAN_ID, List.of(), List.of(supply)))
                .isInstanceOf(LhAnnouncementEnrichmentRejectedException.class)
                .hasMessage("월 임대료 형식이 올바르지 않습니다.");
    }

    private LhAnnouncementDetailSource complexDetail(int order, String complexName, String expectedMoveInYearMonth) {
        return new LhAnnouncementDetailSource(
                order, PAN_ID, "COMPLEX", complexName, null, null, null, null, null,
                expectedMoveInYearMonth, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private LhAnnouncementSupplySource supply(int order, String complexName, String housingTypeName) {
        return supply(order, complexName, housingTypeName, "100", "20", "10,000,000", "200,000");
    }

    private LhAnnouncementSupplySource supply(
            int order,
            String complexName,
            String housingTypeName,
            String totalUnitCount,
            String suppliedUnitCount,
            String deposit,
            String rent
    ) {
        return new LhAnnouncementSupplySource(order, PAN_ID, new LhAnnouncementSupplySourceSnapshot(
                complexName, housingTypeName, null, null, totalUnitCount, suppliedUnitCount, deposit, rent
        ));
    }
}
