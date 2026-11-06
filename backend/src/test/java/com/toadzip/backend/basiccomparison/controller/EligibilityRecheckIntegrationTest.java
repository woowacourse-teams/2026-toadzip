package com.toadzip.backend.basiccomparison.controller;

import static com.toadzip.backend.basiccomparison.domain.EligibilityWork.Kind.NOTICE;
import static com.toadzip.backend.basiccomparison.domain.EligibilityWork.Kind.PROFILE;
import static com.toadzip.backend.basiccomparison.domain.ProfileField.HOUSEHOLD_MONTHLY_INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.basiccomparison.ComparisonFixtures;
import com.toadzip.backend.basiccomparison.EligibilityFixtures;
import com.toadzip.backend.basiccomparison.domain.ComparisonContent;
import com.toadzip.backend.basiccomparison.domain.ComparisonProfile;
import com.toadzip.backend.basiccomparison.domain.EligibilityDecision.Status;
import com.toadzip.backend.basiccomparison.domain.EligibilityWork;
import com.toadzip.backend.basiccomparison.domain.FamilyDetailsState;
import com.toadzip.backend.basiccomparison.domain.InputValue;
import com.toadzip.backend.basiccomparison.domain.ProfileField;
import com.toadzip.backend.basiccomparison.dto.ComparisonRequests.SaveProfile;
import com.toadzip.backend.basiccomparison.repository.ComparisonAnnouncementRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonJsonCodec;
import com.toadzip.backend.basiccomparison.repository.ComparisonProfileRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonReferenceRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonRegionMembership;
import com.toadzip.backend.basiccomparison.repository.EligibilityResultRepository;
import com.toadzip.backend.basiccomparison.repository.EligibilityWorkRepository;
import com.toadzip.backend.basiccomparison.service.EligibilityRecheckTransactions;
import com.toadzip.backend.basiccomparison.service.EligibilityRecheckWorker;
import com.toadzip.backend.basiccomparison.service.EligibilitySnapshotService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.node.ObjectNode;

class EligibilityRecheckIntegrationTest extends ComparisonIntegrationSupport {
    @Autowired EligibilityRecheckWorker worker;
    @Autowired EligibilityRecheckTransactions writes;
    @Autowired EligibilitySnapshotService snapshots;
    @Autowired EligibilityResultRepository results;
    @Autowired EligibilityWorkRepository jobs;
    @Autowired ApplicationContext context;

    @Test
    void 새_지원가능_안내는_회원별로_격리하고_확인처리에_CSRF를_요구한다() throws Exception {
        long notice = notice();
        save(0, 3000000);
        assertThat(worker.runOnce(PROFILE)).isTrue();
        UUID event = results.discovery(USER, notice).orElseThrow().eventId();
        discoveries().andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].eventId").value(event.toString()));
        mvc.perform(get("/api/v2/eligibility/processing").with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.state").value("DONE"));
        mvc.perform(get("/api/v2/eligibility/discoveries")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v2/eligibility/discoveries/" + notice + "/ack")
                        .param("eventId", event.toString()).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isForbidden());
        long anotherUser = USER + 1;
        jdbc.update("INSERT INTO users(id, login_identifier, created_at) VALUES (?, ?, now())",
                anotherUser, "recheck-other-user");
        mvc.perform(get("/api/v2/eligibility/discoveries")
                        .with(user(Long.toString(anotherUser)).roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(post("/api/v2/eligibility/discoveries/" + notice + "/ack")
                        .param("eventId", event.toString())
                        .with(user(Long.toString(anotherUser)).roles("USER")).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(results.discovery(USER, notice).orElseThrow().acknowledgedAt()).isNull();
        acknowledge(notice, event).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        discoveries().andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 정보저장_완료와_재판정_완료를_구분하고_최신완료_한벌만_교체한다() throws Exception {
        long notice = notice();
        save(0, 3000000).andExpect(jsonPath("$.data.reevaluationState").value("PENDING"));
        detail(notice).andExpect(jsonPath("$.data.freshness").value("RECHECKING"))
                .andExpect(jsonPath("$.data.result").doesNotExist());
        assertThat(jobs.profileProgress(USER).requestedVersion()).isOne();
        assertThat(results.find(USER, notice)).isEmpty();
        assertThat(worker.runOnce(PROFILE)).isTrue();
        detail(notice).andExpect(jsonPath("$.data.freshness").value("CURRENT"))
                .andExpect(jsonPath("$.data.result.status").value("ELIGIBLE"));
        save(1, 5000000);
        detail(notice).andExpect(jsonPath("$.data.staleReasons[0]").value("PROFILE_CHANGED"))
                .andExpect(jsonPath("$.data.result").doesNotExist());
        discoveries().andExpect(jsonPath("$.data.items.length()").value(0));
        assertThat(worker.runOnce(PROFILE)).isTrue();
        detail(notice).andExpect(jsonPath("$.data.result.status").value("INELIGIBLE"));
        assertThat(results.find(USER, notice).orElseThrow().basis().profileVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparison_latest_results"
                + " WHERE user_id = ? AND announcement_id = ?", Long.class, USER, notice)).isOne();
    }

    @Test
    void 연속수정의_오래된_작업은_최신결과와_발견과_커서를_덮어쓰지_않는다() throws Exception {
        long notice = notice();
        save(0, 3000000);
        EligibilityWork older = writes.claim(PROFILE).orElseThrow();
        var earlierResult = snapshots.evaluate(USER, notice);
        save(1, 5000000);
        EligibilityWork newer = writes.claim(PROFILE).orElseThrow();
        assertThat(writes.commit(newer, snapshots.evaluate(USER, notice))).isTrue();
        writes.progress(newer, notice, true);
        assertThat(writes.commit(older, earlierResult)).isFalse();
        writes.progress(older, Long.MAX_VALUE, true);
        var result = results.find(USER, notice).orElseThrow();
        assertThat(result.basis().profileVersion()).isEqualTo(2);
        assertThat(result.result().status()).isEqualTo(Status.INELIGIBLE);
        assertThat(results.discovery(USER, notice)).isEmpty();
        assertThat(jobs.profileProgress(USER).cursorAnnouncementId()).isEqualTo(notice);
    }

    @Test
    void 중단된_선점은_만료후_재시작하고_이전_claim은_반영하지_못한다() throws Exception {
        long notice = notice();
        save(0, 3000000);
        EligibilityWork expired = writes.claim(PROFILE).orElseThrow();
        jdbc.update("UPDATE comparison_profile_rechecks SET lease_until = '1970-01-01 UTC' WHERE user_id = ?", USER);
        EligibilityWork recovered = writes.claim(PROFILE).orElseThrow();
        assertThat(recovered.token()).isNotEqualTo(expired.token());
        var result = snapshots.evaluate(USER, notice);
        assertThat(writes.commit(expired, result)).isFalse();
        assertThat(writes.commit(recovered, result)).isTrue();
        writes.progress(recovered, notice, true);
        detail(notice).andExpect(jsonPath("$.data.freshness").value("CURRENT"));
    }

    @Test
    void 결과저장_직후_중단되어도_재시도는_같은_발견과_확인상태를_보존한다() throws Exception {
        long notice = notice();
        save(0, 3000000);
        EligibilityWork first = writes.claim(PROFILE).orElseThrow();
        var evaluated = snapshots.evaluate(USER, notice);
        assertThat(writes.commit(first, evaluated)).isTrue();
        UUID event = results.discovery(USER, notice).orElseThrow().eventId();
        acknowledge(notice, event).andExpect(status().isNoContent());
        writes.retry(first, "SIMULATED_INTERRUPTION");
        readyProfile();
        EligibilityWork retry = writes.claim(PROFILE).orElseThrow();
        assertThat(retry.cursor()).isZero();
        assertThat(writes.commit(retry, evaluated)).isTrue();
        var discovery = results.discovery(USER, notice).orElseThrow();
        assertThat(discovery.eventId()).isEqualTo(event);
        assertThat(discovery.acknowledgedAt()).isNotNull();
    }

    @Test
    void 일부완료후_반복_만료되어도_성공한_커서부터_이어간다() throws Exception {
        long firstNotice = notice();
        long secondNotice = notice();
        save(0, 3000000);
        EligibilityWork first = writes.claim(PROFILE).orElseThrow();
        assertThat(writes.commit(first, snapshots.evaluate(USER, firstNotice))).isTrue();
        assertThat(writes.checkpoint(first, firstNotice)).isTrue();
        jdbc.update("UPDATE comparison_profile_rechecks SET lease_until = '1970-01-01 UTC' WHERE user_id = ?", USER);
        EligibilityWork next = writes.claim(PROFILE).orElseThrow();
        assertThat(next.cursor()).isEqualTo(firstNotice);
        assertThat(writes.commit(next, snapshots.evaluate(USER, secondNotice))).isTrue();
        assertThat(writes.checkpoint(next, secondNotice)).isTrue();
        writes.progress(next, secondNotice, true);
        assertThat(jobs.profileProgress(USER).state()).isEqualTo("DONE");
        assertThat(results.find(USER, secondNotice)).isPresent();
    }

    @Test
    void 같은_기준의_대조는_진행중인_선점과_커서를_초기화하지_않는다() throws Exception {
        long notice = notice();
        EligibilityWork job = writes.claim(NOTICE).orElseThrow();
        String key = snapshots.basis(USER, notice).noticeKey();
        writes.observe(notice, job.revision(), key);
        assertThat(writes.checkpoint(job, 42)).isTrue();
        writes.observe(notice, job.revision(), key);
        assertThat(jdbc.queryForObject("SELECT cursor_user_id FROM comparison_notice_rechecks"
                        + " WHERE announcement_id = ?",
                Long.class, notice)).isEqualTo(42);
        assertThat(jdbc.queryForObject("SELECT claim_token FROM comparison_notice_rechecks WHERE announcement_id = ?",
                UUID.class, notice)).isEqualTo(job.token());
    }

    @Test
    void 재판정이_먼저_완료되어도_실제_처리기준을_남겨_날짜변경을_복구한다() throws Exception {
        long notice = notice();
        save(0, 3000000);
        assertThat(worker.runOnce(PROFILE)).isTrue();
        var state = jobs.notice(notice, false).orElseThrow();
        assertThat(state.observedBasis()).isEqualTo(snapshots.basis(USER, notice).noticeKey());
        var futureClock = Clock.fixed(Instant.parse("2026-11-01T05:00:00Z"),
                ZoneOffset.UTC);
        var announcements = context.getBean(
                ComparisonAnnouncementRepository.class);
        var futureSnapshots = new EligibilitySnapshotService(announcements,
                context.getBean(ComparisonProfileRepository.class),
                context.getBean(ComparisonReferenceRepository.class),
                context.getBean(ComparisonRegionMembership.class),
                context.getBean(ComparisonJsonCodec.class), jobs, futureClock);
        var futureWrites = new EligibilityRecheckTransactions(jobs, results, futureSnapshots, futureClock, 120);
        var futureWorker = new EligibilityRecheckWorker(futureWrites, futureSnapshots, jobs, results,
                announcements, futureClock, 32);
        futureWorker.reconcileOnce();
        assertThat(jobs.notice(notice, false).orElseThrow().generation()).isEqualTo(state.generation() + 1);
        assertThat(jdbc.queryForObject("SELECT state FROM comparison_notice_rechecks WHERE announcement_id = ?",
                String.class, notice)).isEqualTo("PENDING");
        assertThat(futureWorker.runOnce(NOTICE)).isTrue();
        assertThat(results.find(USER, notice).orElseThrow().basis().applicationStatus()).isEqualTo("CLOSED");
        assertThat(results.find(USER, notice).orElseThrow().result()).isNull();
    }

    @Test
    void 취소는_자격미충족이_아니며_대기중인_지원가능_안내를_즉시_숨긴다() throws Exception {
        long notice = notice();
        save(0, 3000000);
        assertThat(worker.runOnce(PROFILE)).isTrue();
        discoveries().andExpect(jsonPath("$.data.items.length()").value(1));
        EligibilityWork previous = writes.claim(NOTICE).orElseThrow();
        var before = snapshots.evaluate(USER, notice);
        jdbc.update("UPDATE announcements SET status = 'CANCELLATION' WHERE id = ?", notice);
        assertThat(writes.commit(previous, before)).isFalse();
        detail(notice).andExpect(jsonPath("$.data.availability").value("CANCELLED"))
                .andExpect(jsonPath("$.data.freshness").value("NOT_AVAILABLE"))
                .andExpect(jsonPath("$.data.result").doesNotExist());
        discoveries().andExpect(jsonPath("$.data.items.length()").value(0));
        assertThat(worker.runOnce(NOTICE)).isTrue();
        assertThat(results.find(USER, notice).orElseThrow().basis().availability()).isEqualTo("CANCELLED");
        assertThat(results.discovery(USER, notice)).isEmpty();
    }

    @Test
    void 확인후_미충족이었다가_다시_가능해지면_새로운_발견으로_안내한다() throws Exception {
        long notice = notice();
        save(0, 3000000);
        assertThat(worker.runOnce(PROFILE)).isTrue();
        UUID first = results.discovery(USER, notice).orElseThrow().eventId();
        acknowledge(notice, first).andExpect(status().isNoContent());
        save(1, 5000000);
        assertThat(worker.runOnce(PROFILE)).isTrue();
        save(2, 3000000);
        assertThat(worker.runOnce(PROFILE)).isTrue();
        UUID second = results.discovery(USER, notice).orElseThrow().eventId();
        assertThat(second).isNotEqualTo(first);
        acknowledge(notice, first).andExpect(status().isConflict());
        discoveries().andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void 정정으로_root가_합쳐지면_이전_키의_결과와_발견을_중복_노출하지_않는다() throws Exception {
        long original = notice();
        long correction = notice();
        save(0, 3000000);
        assertThat(worker.runOnce(PROFILE)).isTrue();
        assertThat(results.find(USER, correction)).isPresent();
        jdbc.update("UPDATE announcements SET previous_announcement_id = ?, status = 'CORRECTION' WHERE id = ?",
                original, correction);
        assertThat(jobs.notice(correction, false).orElseThrow().retired()).isTrue();
        discoveries().andExpect(jsonPath("$.data.items.length()").value(0));
        for (int index = 0; index < 4; index++) {
            worker.runOnce(NOTICE);
        }
        assertThat(results.find(USER, correction)).isEmpty();
        assertThat(results.find(USER, original).orElseThrow().basis().announcementId()).isEqualTo(correction);
        detail(original).andExpect(jsonPath("$.data.freshness").value("CURRENT"))
                .andExpect(jsonPath("$.data.announcementId").value(correction));
    }

    @Test
    void 일부_선택지_취소는_나머지_가능한_선택지를_유지하며_이전_발견은_보류한다() throws Exception {
        long notice = announcement();
        var content = EligibilityFixtures.d01();
        publish(notice, saveDraft(notice, null, 0, content)).andExpect(status().isOk());
        var profile = new ComparisonProfile(
                FamilyDetailsState.NOT_STARTED, Map.of(
                        ProfileField.BIRTH_DATE,
                        InputValue.provided("1997-01-10"),
                        ProfileField.FAMILY_TYPE,
                        InputValue.provided("UNMARRIED")));
        mvc.perform(put("/api/v2/me/eligibility-profile").with(user(Long.toString(USER)).roles("USER"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SaveProfile(0L, 3, profile))))
                .andExpect(status().isOk());
        assertThat(worker.runOnce(PROFILE)).isTrue();
        var revised = (ObjectNode) json.valueToTree(content);
        ((ObjectNode) revised.path("options").get(0))
                .put("recruitmentStatus", "CANCELLED");
        var next = json.treeToValue(revised, ComparisonContent.class);
        publish(notice, saveDraft(notice, null, 0, next)).andExpect(status().isOk());
        detail(notice).andExpect(jsonPath("$.data.staleReasons[0]").value("NOTICE_CHANGED"));
        discoveries().andExpect(jsonPath("$.data.items.length()").value(0));
        assertThat(worker.runOnce(NOTICE)).isTrue();
        detail(notice).andExpect(jsonPath("$.data.result.status").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.result.options[0].recruitmentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.eligibleOptionIdsInRegion[0]").value("giheung-youth"));
    }

    private long notice() throws Exception {
        long notice = announcement();
        publish(notice, saveDraft(notice, null, 0, EligibilityFixtures.d05())).andExpect(status().isOk());
        return notice;
    }

    private ResultActions save(long version, long income) throws Exception {
        return mvc.perform(put("/api/v2/me/eligibility-profile").with(user(Long.toString(USER)).roles("USER"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                                new SaveProfile(version, 3, ComparisonFixtures.with(
                                        ComparisonFixtures.b(), HOUSEHOLD_MONTHLY_INCOME, income)))))
                .andExpect(status().isOk());
    }

    private ResultActions detail(long notice) throws Exception {
        return mvc.perform(get("/api/v2/announcements/" + notice + "/eligibility")
                .with(user(Long.toString(USER)).roles("USER"))).andExpect(status().isOk());
    }

    private ResultActions discoveries() throws Exception {
        return mvc.perform(get("/api/v2/eligibility/discoveries").with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk());
    }

    private ResultActions acknowledge(long notice, UUID event) throws Exception {
        return mvc.perform(post("/api/v2/eligibility/discoveries/" + notice + "/ack")
                .param("eventId", event.toString()).with(user(Long.toString(USER)).roles("USER")).with(csrf()));
    }

    private void readyProfile() {
        jdbc.update("UPDATE comparison_profile_rechecks SET next_attempt_at = '1970-01-01 UTC'"
                + " WHERE user_id = ?", USER);
    }
}
