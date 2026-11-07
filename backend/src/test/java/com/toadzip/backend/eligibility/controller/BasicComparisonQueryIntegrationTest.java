package com.toadzip.backend.eligibility.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.eligibility.BasicComparisonFixtures;
import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.ProfileField;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveProfile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

class BasicComparisonQueryIntegrationTest extends EligibilityIntegrationSupport {
    @Test
    void 초안은_비교하지_않고_공개후_세대소득_근거와_표시정보를_반환한다() throws Exception {
        long announcement = announcement();
        var draft = saveDraft(announcement, null, 0, BasicComparisonFixtures.sacheonWithInformation());
        detail(USER, announcement)
                .andExpect(jsonPath("$.data.availability").value("NOT_STRUCTURED"))
                .andExpect(jsonPath("$.data.result").isEmpty());
        publish(announcement, draft).andExpect(status().isOk());
        save(USER, 0, BasicComparisonFixtures.b());
        var result =
                response(
                        detail(USER, announcement)
                                .andExpect(header().string("Cache-Control", "no-store"))
                                .andExpect(jsonPath("$.data.profileVersion").value(1))
                                .andExpect(
                                        jsonPath("$.data.result.options[0].status")
                                                .value("MATCHED"))
                                .andExpect(
                                        jsonPath(
                                                        "$.data.result.options[0].checks[?(@.id =="
                                                            + " 'income')].reading.comparedValues.providedValue")
                                                .value(org.hamcrest.Matchers.contains(3000000)))
                                .andExpect(
                                        jsonPath(
                                                        "$.data.result.options[0].checks[?(@.id =="
                                                            + " 'income')].reading.comparedValues.comparisonLimit")
                                                .value(org.hamcrest.Matchers.contains(3519762)))
                                .andExpect(jsonPath("$.data.result.quantities[2].value").value(110))
                                .andExpect(jsonPath("$.data.result.rentOffers.length()").value(2))
                                .andExpect(
                                        jsonPath("$.data.result.schedules[0].targetAsWritten")
                                                .isNotEmpty())
                                .andExpect(
                                        jsonPath("$.data.result.evidences[0].documentId")
                                                .value("D05")));
        assertThat(result.toString())
                .doesNotContain("canApply", "eligible", "selectionResults", "rank", "score");
        summary(USER)
                .andExpect(jsonPath("$.data.generalBasicMatchedCount").value(1))
                .andExpect(jsonPath("$.data.generalInputNeededCount").value(0));
    }

    @Test
    void 일반경로_한도초과와_그_근거만_표시하고_다른_경로_안내는_유지한다() throws Exception {
        long announcement = published(BasicComparisonFixtures.sacheon());
        save(
                USER,
                0,
                BasicComparisonFixtures.with(
                        BasicComparisonFixtures.b(), ProfileField.HOUSEHOLD_MONTHLY_INCOME, 3519763L));
        detail(USER, announcement)
                .andExpect(jsonPath("$.data.result.options[0].status").value("NOT_MATCHED"))
                .andExpect(
                        jsonPath(
                                        "$.data.result.options[0].checks[?(@.id =="
                                                + " 'income')].reading.status")
                                .value(org.hamcrest.Matchers.contains("NOT_SATISFIED")))
                .andExpect(
                        jsonPath("$.data.result.directChecks[?(@.kind == 'OTHER_ROUTE')].id")
                                .value(org.hamcrest.Matchers.contains("other")));
        summary(USER).andExpect(jsonPath("$.data.generalBasicMatchedCount").value(0));
        save(
                USER,
                1,
                BasicComparisonFixtures.with(
                        BasicComparisonFixtures.b(), ProfileField.HOUSEHOLD_MONTHLY_INCOME, 3519762L));
        detail(USER, announcement)
                .andExpect(jsonPath("$.data.result.options[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.data.profileVersion").value(2));
        summary(USER).andExpect(jsonPath("$.data.generalBasicMatchedCount").value(1));
    }

    @Test
    void 선택_가족정보_생략은_선택적_미입력으로_남기고_세대소득_비교를_유지한다() throws Exception {
        long announcement = published(BasicComparisonFixtures.sacheon());
        save(USER, 0, BasicComparisonFixtures.skipped());
        var data =
                response(
                        detail(USER, announcement)
                                .andExpect(
                                        jsonPath("$.data.result.options[0].status")
                                                .value("INPUT_NEEDED"))
                                .andExpect(
                                        jsonPath(
                                                        "$.data.result.options[0].checks[?(@.id =="
                                                                + " 'income')].reading.status")
                                                .value(
                                                        org.hamcrest.Matchers.contains(
                                                                "SATISFIED"))));
        for (var missing : data.path("result").path("options").get(0).path("missingInputs")) {
            assertThat(missing.path("optional").asBoolean()).isTrue();
            assertThat(missing.path("skipped").asBoolean()).isTrue();
        }
        assertThat(data.path("result").path("options").get(0).path("missingInputs").size())
                .isPositive();
        summary(USER)
                .andExpect(jsonPath("$.data.generalBasicMatchedCount").value(0))
                .andExpect(jsonPath("$.data.generalInputNeededCount").value(1));
    }

    @Test
    void D11의_공통조건_충족을_일반_신청경로_충족으로_집계하지_않는다() throws Exception {
        long announcement = published(BasicComparisonFixtures.shPermanent());
        save(USER, 0, BasicComparisonFixtures.singleParent());
        detail(USER, announcement)
                .andExpect(jsonPath("$.data.result.options[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.data.result.options[0].coverage").value("COMMON_ONLY"))
                .andExpect(
                        jsonPath(
                                        "$.data.result.directChecks[?(@.kind =="
                                                + " 'REQUIRED_QUALIFICATION')].id")
                                .value(org.hamcrest.Matchers.contains("qualification")))
                .andExpect(jsonPath("$.data.result.directChecks[0].items.length()").value(9));
        summary(USER)
                .andExpect(jsonPath("$.data.generalBasicMatchedCount").value(0))
                .andExpect(jsonPath("$.data.generalInputNeededCount").value(0))
                .andExpect(jsonPath("$.data.requiredQualificationNoticeCount").value(1))
                .andExpect(jsonPath("$.data.comparedAnnouncementCount").value(1));
    }

    @Test
    void D03은_사전선정_제한을_필수_자격으로_알리고_개인_공고에_남긴다() throws Exception {
        long announcement = published(BasicComparisonFixtures.anyang());
        save(USER, 0, BasicComparisonFixtures.b());
        detail(USER, announcement)
                .andExpect(jsonPath("$.data.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.result.scope").value("INDIVIDUAL"))
                .andExpect(
                        jsonPath("$.data.result.directChecks[0].kind")
                                .value("REQUIRED_QUALIFICATION"))
                .andExpect(
                        jsonPath("$.data.result.directChecks[0].title")
                                .value(org.hamcrest.Matchers.containsString("일반인은 청약할 수 없습니다")));
        summary(USER)
                .andExpect(jsonPath("$.data.generalBasicMatchedCount").value(0))
                .andExpect(jsonPath("$.data.requiredQualificationNoticeCount").value(1));
    }

    @Test
    void D14의_일반_비교와_우선공급_안내는_함께_제공한다() throws Exception {
        long announcement = published(BasicComparisonFixtures.dasan());
        save(USER, 0, BasicComparisonFixtures.b());
        detail(USER, announcement)
                .andExpect(jsonPath("$.data.result.options[0].status").value("MATCHED"))
                .andExpect(
                        jsonPath(
                                        "$.data.result.directChecks[?(@.kind =="
                                                + " 'PRIORITY_OPPORTUNITY')].id")
                                .value(org.hamcrest.Matchers.contains("priority")))
                .andExpect(
                        jsonPath(
                                        "$.data.result.options[0].checks[?(@.id =="
                                            + " 'income')].reading.comparedValues.comparisonLimit")
                                .value(org.hamcrest.Matchers.contains(6718867)));
        summary(USER).andExpect(jsonPath("$.data.generalBasicMatchedCount").value(1));
    }

    @Test
    void 기업_신청은_개인_집계에서_제외하고_직접_접근시_이유를_알린다() throws Exception {
        long announcement = published(BasicComparisonFixtures.company());
        detail(USER, announcement)
                .andExpect(jsonPath("$.data.availability").value("OUT_OF_SCOPE"))
                .andExpect(jsonPath("$.data.result.scope").value("OUT_OF_SCOPE"))
                .andExpect(jsonPath("$.data.result.options").isEmpty());
        summary(USER)
                .andExpect(jsonPath("$.data.comparedAnnouncementCount").value(0))
                .andExpect(jsonPath("$.data.notStructuredCount").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"D06", "D08", "D09", "D17"})
    void 산업단지나_일자리_제목으로_현재_일반_개인_경로를_제외하지_않는다(String document) throws Exception {
        long announcement =
                published(BasicComparisonFixtures.classification(document, "산업단지·일자리 모집 분류 검증"));
        detail(USER, announcement)
                .andExpect(jsonPath("$.data.result.scope").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.data.result.options[0].coverage").value("GENERAL"))
                .andExpect(jsonPath("$.data.result.options[0].status").value("NOT_EVALUATED"));
        // Classification fixtures do not claim to implement every source rule.
        summary(USER)
                .andExpect(jsonPath("$.data.generalBasicMatchedCount").value(0))
                .andExpect(jsonPath("$.data.comparedAnnouncementCount").value(1));
    }

    @Test
    void 미확정_자녀가산을_낮은_한도의_미충족이나_확인된_충족으로_만들지_않는다() throws Exception {
        long announcement = published(BasicComparisonFixtures.sacheon());
        save(
                USER,
                0,
                BasicComparisonFixtures.with(
                        BasicComparisonFixtures.singleParent(), ProfileField.RESIDENCE_REGION, "48240"));
        detail(USER, announcement)
                .andExpect(jsonPath("$.data.result.options[0].status").value("PARTIALLY_COMPARED"))
                .andExpect(
                        jsonPath(
                                        "$.data.result.options[0].checks[?(@.id =="
                                                + " 'income')].reading.status")
                                .value(org.hamcrest.Matchers.contains("DIRECT_CHECK_REQUIRED")));
        summary(USER).andExpect(jsonPath("$.data.generalBasicMatchedCount").value(0));
    }

    @Test
    void 미구조화_공고가_한_묶음보다_많아도_다음_공개본까지_한번씩_센다() throws Exception {
        for (int index = 0; index < 33; index++) {
            announcement();
        }
        published(BasicComparisonFixtures.sacheon());
        save(USER, 0, BasicComparisonFixtures.b());
        summary(USER)
                .andExpect(jsonPath("$.data.generalBasicMatchedCount").value(1))
                .andExpect(jsonPath("$.data.notStructuredCount").value(33))
                .andExpect(jsonPath("$.data.comparedAnnouncementCount").value(1));
    }

    @Test
    void 선택지가_여러개여도_한_공고로_집계한다() throws Exception {
        EligibilityContent base = BasicComparisonFixtures.sacheon();
        EligibilityContent content =
                new EligibilityContent(
                        base.metadata(),
                        base.documents(),
                        base.evidences(),
                        List.of(
                                base.supplyUnits().getFirst(),
                                new EligibilityContent.SupplyUnit("another", "집계 검증 단위", List.of())),
                        base.paths(),
                        List.of(
                                base.options().getFirst(),
                                new EligibilityContent.ApplicationOption(
                                        "another-general",
                                        "another",
                                        "ordinary",
                                        EligibilityContent.RecruitmentStatus.RECRUITING)),
                        base.guidances(),
                        base.quantities(),
                        base.rentOffers(),
                        base.schedules());
        long announcement = published(content);
        save(USER, 0, BasicComparisonFixtures.b());
        detail(USER, announcement).andExpect(jsonPath("$.data.result.options.length()").value(2));
        summary(USER).andExpect(jsonPath("$.data.generalBasicMatchedCount").value(1));
    }

    @Test
    void 검토된_접수일정을_우선하며_조건부_접수만_있으면_집계하지_않는다() throws Exception {
        long announcement = published(BasicComparisonFixtures.sacheon());
        save(USER, 0, BasicComparisonFixtures.b());
        jdbc.update(
                "UPDATE announcements SET application_schedule_reviewed = true,"
                    + " application_start_date = '2026-01-01', application_end_date = '2026-01-02'"
                    + " WHERE id = ?",
                announcement);
        jdbc.update(
                """
                INSERT INTO announcement_application_schedules
                    (announcement_id, state, start_date, end_date, source_url)
                VALUES (?, 'CONFIRMED', '2026-10-28', '2026-10-30', 'https://example.test/confirmed')
                """,
                announcement);
        summary(USER).andExpect(jsonPath("$.data.generalBasicMatchedCount").value(1));
        jdbc.update(
                "UPDATE announcement_application_schedules SET state = 'CONDITIONAL', "
                        + "application_condition = '잔여 물량 발생 시' WHERE announcement_id = ?",
                announcement);
        summary(USER)
                .andExpect(jsonPath("$.data.generalBasicMatchedCount").value(0))
                .andExpect(jsonPath("$.data.comparedAnnouncementCount").value(0));
    }

    @Test
    void 최신_정정공고가_준비되지_않아도_이전결과로_대체하지_않으며_취소시_결과를_숨긴다() throws Exception {
        long original = published(BasicComparisonFixtures.sacheon());
        long corrected = announcement();
        jdbc.update(
                "UPDATE announcements SET previous_announcement_id = ?, status = '정정공고' WHERE id ="
                        + " ?",
                original,
                corrected);
        detail(USER, original)
                .andExpect(jsonPath("$.data.announcementId").value(corrected))
                .andExpect(jsonPath("$.data.availability").value("NOT_STRUCTURED"));
        summary(USER)
                .andExpect(jsonPath("$.data.notStructuredCount").value(1))
                .andExpect(jsonPath("$.data.comparedAnnouncementCount").value(0));
        publish(corrected, saveDraft(corrected, null, 0, BasicComparisonFixtures.sacheon()))
                .andExpect(status().isOk());
        detail(USER, original).andExpect(jsonPath("$.data.availability").value("AVAILABLE"));
        jdbc.update("UPDATE announcements SET status = 'CANCELLATION' WHERE id = ?", corrected);
        detail(USER, original)
                .andExpect(jsonPath("$.data.availability").value("CANCELLED"))
                .andExpect(jsonPath("$.data.result").isEmpty());
        summary(USER).andExpect(jsonPath("$.data.comparedAnnouncementCount").value(0));
    }

    @Test
    void 개인별_비교와_요약은_다른_회원의_결과를_재사용하지_않는다() throws Exception {
        long announcement = published(BasicComparisonFixtures.sacheon());
        long other = USER + 2;
        jdbc.update(
                "INSERT INTO users (id, login_identifier, created_at) VALUES (?, 'query-other',"
                        + " now())",
                other);
        save(USER, 0, BasicComparisonFixtures.b());
        save(
                other,
                0,
                BasicComparisonFixtures.with(
                        BasicComparisonFixtures.b(), ProfileField.HOUSEHOLD_MONTHLY_INCOME, 4000000L));
        detail(USER, announcement)
                .andExpect(jsonPath("$.data.result.options[0].status").value("MATCHED"));
        detail(other, announcement)
                .andExpect(jsonPath("$.data.result.options[0].status").value("NOT_MATCHED"));
        summary(other).andExpect(jsonPath("$.data.generalBasicMatchedCount").value(0));
        summary(USER).andExpect(jsonPath("$.data.generalBasicMatchedCount").value(1));
    }

    private long published(EligibilityContent content) throws Exception {
        long announcement = announcement();
        publish(announcement, saveDraft(announcement, null, 0, content)).andExpect(status().isOk());
        return announcement;
    }

    private void save(long member, long version, EligibilityProfile profile) throws Exception {
        mvc.perform(
                        put("/api/v2/me/eligibility-profile")
                                .with(user(Long.toString(member)).roles("USER"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                new SaveProfile(version, 3, profile))))
                .andExpect(status().isOk());
    }

    private ResultActions detail(long member, long announcement) throws Exception {
        return mvc.perform(
                        get("/api/v2/announcements/" + announcement + "/basic-comparison")
                                .with(user(Long.toString(member)).roles("USER")))
                .andExpect(status().isOk());
    }

    private ResultActions summary(long member) throws Exception {
        return mvc.perform(
                        get("/api/v2/eligibility/summary")
                                .with(user(Long.toString(member)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
