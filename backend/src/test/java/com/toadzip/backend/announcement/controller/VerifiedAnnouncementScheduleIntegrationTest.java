package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.request.VerifiedApplicationSchedulesRequest;
import com.toadzip.backend.announcement.dto.request.VerifiedApplicationSchedulesRequest.Schedule;
import com.toadzip.backend.announcement.dto.request.VerifiedLhRevisionRequest;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.service.VerifiedApplicationScheduleService;
import com.toadzip.backend.announcement.service.VerifiedLhRevisionService;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(VerifiedAnnouncementScheduleIntegrationTest.FixedClock.class)
@Transactional
class VerifiedAnnouncementScheduleIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private VerifiedApplicationScheduleService scheduleService;
    @Autowired private VerifiedLhRevisionService revisionService;
    @Autowired private EntityManager entityManager;

    @Test
    void 조건부_후순위_날짜를_일반_접수중과_구분하고_공식_종료시각을_제공한다() throws Exception {
        Announcement announcement = announcement("2015122300020681");
        mvc.perform(put("/api/admin/announcements/{id}/application-schedules", announcement.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"schedules":[
                                  {"supplyRank":"1·2순위","state":"CONFIRMED",
                                   "startDate":"2026-09-14","endDate":"2026-09-14",
                                   "startTime":"10:00","endTime":"16:00",
                                   "sourceUrl":"https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68429633",
                                   "sourcePage":6},
                                  {"supplyRank":"무순위","state":"CONDITIONAL",
                                   "condition":"선순위 접수 결과에 따라 진행",
                                   "startDate":"2026-09-15","endDate":"2026-09-15",
                                   "startTime":"10:00","endTime":"16:00",
                                   "sourceUrl":"https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68429633",
                                   "sourcePage":6}
                                ]}
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/announcements/{id}", announcement.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationStatus").value("CONDITIONAL"))
                .andExpect(jsonPath("$.data.applicationStartAt").value("2026-09-14"))
                .andExpect(jsonPath("$.data.applicationEndAt").value("2026-09-15"))
                .andExpect(jsonPath("$.data.applicationSchedules[1].supplyRank").value("무순위"))
                .andExpect(jsonPath("$.data.applicationSchedules[1].endTime").value("16:00:00"));
        mvc.perform(get("/api/v1/announcements").param("applicationStatuses", "APPLYING"))
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/v1/announcements").param("applicationStatuses", "CONDITIONAL"))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(announcement.getId()));
    }

    @Test
    void 일정_사이의_빈날은_접수중이나_기간검색에_포함하지_않고_미확인_시각은_null이다() throws Exception {
        Announcement announcement = announcement("2015122300020681");
        scheduleService.replace(announcement.getId(), new VerifiedApplicationSchedulesRequest(List.of(
                schedule(null, ApplicationScheduleState.CONFIRMED, 14),
                schedule(null, ApplicationScheduleState.CONFIRMED, 16))));

        mvc.perform(get("/api/v1/announcements/{id}", announcement.getId()))
                .andExpect(jsonPath("$.data.applicationStatus").value("BEFORE_APPLICATION"))
                .andExpect(jsonPath("$.data.applicationSchedules[0].startTime").isEmpty());
        mvc.perform(get("/api/v1/announcements").param("applicationStatuses", "APPLYING"))
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/v1/announcements")
                        .param("applicationFrom", "2026-09-15").param("applicationTo", "2026-09-15"))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 단지와_지역에_해당하는_일정으로_카드_필터와_확정_Dday를_계산한다() throws Exception {
        Announcement announcement = announcement("2015122300020681");
        HousingComplex confirmedComplex = complex(announcement, "11140");
        HousingComplex conditionalComplex = complex(announcement, "11680");
        scheduleService.replace(announcement.getId(), new VerifiedApplicationSchedulesRequest(List.of(
                schedule(confirmedComplex.getId(), ApplicationScheduleState.CONFIRMED, 15),
                schedule(confirmedComplex.getId(), ApplicationScheduleState.CONDITIONAL, 16),
                schedule(conditionalComplex.getId(), ApplicationScheduleState.CONDITIONAL, 15))));

        mvc.perform(get("/api/v1/announcements").param("regionCode", "11680")
                        .param("applicationStatuses", "CONDITIONAL"))
                .andExpect(jsonPath("$.data.items[0].applicationStatus").value("CONDITIONAL"))
                .andExpect(jsonPath("$.data.items[0].dDay").isEmpty());
        mvc.perform(get("/api/v1/announcements").param("regionCode", "11680")
                        .param("applicationStatuses", "APPLYING"))
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/v1/complexes").param("applicationStatuses", "APPLYING")
                        .param("southWestLat", "37").param("southWestLng", "126")
                        .param("northEastLat", "38").param("northEastLng", "128"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].complexId").value(confirmedComplex.getId()))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.dDay").value(0));
        mvc.perform(get("/api/v1/complexes/{id}", conditionalComplex.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentAnnouncements[0].applicationStatus").value("CONDITIONAL"))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].dDay").isEmpty());
    }

    @Test
    void 단지별_접수기간과_Dday를_목록과_상세에서_같은_일정으로_표시한다() throws Exception {
        Announcement announcement = announcement("2015122300020681");
        HousingComplex earlyComplex = complex(announcement, "11140");
        HousingComplex lateComplex = complex(announcement, "11680");
        scheduleService.replace(announcement.getId(), new VerifiedApplicationSchedulesRequest(List.of(
                schedule(earlyComplex.getId(), ApplicationScheduleState.CONFIRMED, 14, 15),
                schedule(lateComplex.getId(), ApplicationScheduleState.CONFIRMED, 24, 25))));

        assertComplexApplicationPeriod(earlyComplex, "APPLYING", 14, 15, 0);
        assertComplexApplicationPeriod(lateComplex, "BEFORE_APPLICATION", 24, 25, 10);
        mvc.perform(get("/api/v1/announcements/{id}", announcement.getId()))
                .andExpect(jsonPath("$.data.applicationStartAt").value("2026-09-14"))
                .andExpect(jsonPath("$.data.applicationEndAt").value("2026-09-25"));
    }

    @Test
    void 공통_확정기간을_현재와_후순위의_조건부_기간보다_우선한다() throws Exception {
        Announcement announcement = announcement("2015122300020681");
        HousingComplex complex = complex(announcement, "11140");
        scheduleService.replace(announcement.getId(), new VerifiedApplicationSchedulesRequest(List.of(
                schedule(null, ApplicationScheduleState.CONFIRMED, 14, 16),
                schedule(complex.getId(), ApplicationScheduleState.CONDITIONAL, 15),
                schedule(complex.getId(), ApplicationScheduleState.CONDITIONAL, 17))));

        assertComplexApplicationPeriod(complex, "APPLYING", 14, 16, 1);
    }

    @Test
    void 현재_조건부_기간에_미래_확정기간과_다른_단지_기간을_섞지_않는다() throws Exception {
        Announcement announcement = announcement("2015122300020681");
        HousingComplex currentComplex = complex(announcement, "11140");
        HousingComplex otherComplex = complex(announcement, "11680");
        scheduleService.replace(announcement.getId(), new VerifiedApplicationSchedulesRequest(List.of(
                schedule(currentComplex.getId(), ApplicationScheduleState.CONDITIONAL, 15),
                schedule(currentComplex.getId(), ApplicationScheduleState.CONFIRMED, 16),
                schedule(otherComplex.getId(), ApplicationScheduleState.CONFIRMED, 25))));

        assertComplexApplicationPeriod(currentComplex, "CONDITIONAL", 15, 15, null);
    }

    @Test
    void 일정_사이에는_다음_접수기간과_그_종료일까지의_Dday를_표시한다() throws Exception {
        Announcement announcement = announcement("2015122300020681");
        HousingComplex complex = complex(announcement, "11140");
        scheduleService.replace(announcement.getId(), new VerifiedApplicationSchedulesRequest(List.of(
                schedule(complex.getId(), ApplicationScheduleState.CONFIRMED, 14),
                schedule(complex.getId(), ApplicationScheduleState.CONFIRMED, 16, 18),
                schedule(complex.getId(), ApplicationScheduleState.CONDITIONAL, 20))));

        assertComplexApplicationPeriod(complex, "BEFORE_APPLICATION", 16, 18, 3);
    }

    @Test
    void 다음_접수가_조건부이면_그_기간을_표시하고_확정_Dday를_만들지_않는다() throws Exception {
        Announcement announcement = announcement("2015122300020681");
        HousingComplex complex = complex(announcement, "11140");
        scheduleService.replace(announcement.getId(), new VerifiedApplicationSchedulesRequest(List.of(
                schedule(complex.getId(), ApplicationScheduleState.CONDITIONAL, 16),
                schedule(complex.getId(), ApplicationScheduleState.CONFIRMED, 20))));

        assertComplexApplicationPeriod(complex, "BEFORE_APPLICATION", 16, 16, null);
    }

    @Test
    void 접수가_끝난_단지는_그_단지의_마지막_종료일만_표시한다() throws Exception {
        Announcement announcement = announcement("2015122300020681");
        HousingComplex closedComplex = complex(announcement, "11140");
        HousingComplex otherComplex = complex(announcement, "11680");
        scheduleService.replace(announcement.getId(), new VerifiedApplicationSchedulesRequest(List.of(
                schedule(closedComplex.getId(), ApplicationScheduleState.CONFIRMED, 12),
                schedule(closedComplex.getId(), ApplicationScheduleState.CONDITIONAL, 14),
                schedule(otherComplex.getId(), ApplicationScheduleState.CONFIRMED, 25))));
        entityManager.flush();

        mvc.perform(get("/api/v1/complexes").param("regionCode", "11140")
                        .param("southWestLat", "37").param("southWestLng", "126")
                        .param("northEastLat", "38").param("northEastLng", "128"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.applicationStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.applicationEndAt").value("2026-09-14"))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.dDay").isEmpty());
        mvc.perform(get("/api/v1/complexes/{id}", closedComplex.getId()))
                .andExpect(jsonPath("$.data.currentAnnouncements").isEmpty());
    }

    @Test
    void 근거가_있는_정정본만_검색하고_기존_ID와_수동_첨부는_유지한다() throws Exception {
        Announcement previous = announcement("2015122300020681");
        Announcement correction = announcement("2015122300020856");
        AnnouncementAttachment manual = AnnouncementAttachment.create(previous, "수동 첨부.pdf",
                AttachmentType.ANNOUNCEMENT, "https://example.com/manual.pdf", 0);
        entityManager.persist(manual);

        mvc.perform(put("/api/admin/announcements/{id}/revision", correction.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"previousAnnouncementId":%d,"previousPanId":"2015122300020681",
                                 "correctedPanId":"2015122300020856",
                                 "evidenceUrl":"https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68429633",
                                 "reason":"공식 정정 공고문에서 원공고와 변경 내용 확인"}
                                """.formatted(previous.getId())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/announcements"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(correction.getId()))
                .andExpect(jsonPath("$.data.items[0].publicationType").value("CORRECTION"));
        mvc.perform(get("/api/v1/announcements/{id}", correction.getId()))
                .andExpect(jsonPath("$.data.revision.previousAnnouncementId").value(previous.getId()))
                .andExpect(jsonPath("$.data.revision.correctedPanId").value("2015122300020856"));
        mvc.perform(get("/api/v1/announcements/{id}", previous.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments[0].attachmentId").value(manual.getId()));
    }

    @Test
    void 다시_수집한_대표기간과_원공고_분류가_확인한_일정과_개정관계를_덮어쓰지_않는다() {
        Announcement previous = announcement("2015122300020681");
        Announcement correction = announcement("2015122300020856");
        revisionService.link(correction.getId(), revision(previous, "2015122300020856"));
        scheduleService.replace(correction.getId(), new VerifiedApplicationSchedulesRequest(List.of(
                schedule(null, ApplicationScheduleState.CONFIRMED, 15))));
        correction.updateFromMyHome(null, null, "재수집 공고", AnnouncementPublicationType.ORIGINAL,
                RentalType.NATIONAL_RENTAL, RecruitmentType.WAITLIST, AgencyCode.LH,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 12, 31), correction.getOriginalUrl(), null);
        entityManager.flush();
        entityManager.clear();

        Announcement stored = announcements.findById(correction.getId()).orElseThrow();
        assertThat(stored.getPreviousAnnouncement().getId()).isEqualTo(previous.getId());
        assertThat(stored.getStatus()).isEqualTo(AnnouncementPublicationType.CORRECTION);
        assertThat(stored.getApplicationStartDate()).isEqualTo("2026-09-15");
        assertThat(stored.getApplicationEndDate()).isEqualTo("2026-09-15");
        assertThat(stored.getCorrectionCancellationReason()).isEqualTo("공식 정정 확인");
    }

    @Test
    void 공고에_없는_단지의_일정과_조건_없는_조건부_일정은_거부한다() {
        Announcement announcement = announcement("2015122300020681");
        assertThatThrownBy(() -> scheduleService.replace(announcement.getId(),
                new VerifiedApplicationSchedulesRequest(List.of(
                        schedule(Long.MAX_VALUE, ApplicationScheduleState.CONFIRMED, 15)))))
                .isInstanceOf(InvalidAnnouncementRequestException.class);
        Schedule missingCondition = new Schedule(null, "후순위", ApplicationScheduleState.CONDITIONAL, null,
                LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 15), null, null,
                "https://apply.lh.or.kr/notice.pdf", 1);
        assertThatThrownBy(() -> scheduleService.replace(announcement.getId(),
                new VerifiedApplicationSchedulesRequest(List.of(missingCondition))))
                .isInstanceOf(InvalidAnnouncementRequestException.class);
        assertThat(announcement.isApplicationScheduleReviewed()).isFalse();
    }

    @Test
    void 불일치_PAN과_개정_순환을_거부한다() {
        Announcement previous = announcement("2015122300020681");
        Announcement correction = announcement("2015122300020856");
        assertThatThrownBy(() -> revisionService.link(correction.getId(), revision(previous, "9999999999999999")))
                .isInstanceOf(InvalidAnnouncementRequestException.class);
        revisionService.link(correction.getId(), revision(previous, "2015122300020856"));
        assertThatThrownBy(() -> revisionService.link(previous.getId(), revision(correction, "2015122300020681")))
                .isInstanceOf(InvalidAnnouncementRequestException.class);
        assertThat(previous.getPreviousAnnouncement()).isNull();
    }

    private VerifiedLhRevisionRequest revision(Announcement previous, String correctedPanId) {
        return new VerifiedLhRevisionRequest(previous.getId(),
                previous.getOriginalUrl().substring(previous.getOriginalUrl().indexOf("panId=") + 6),
                correctedPanId, "https://apply.lh.or.kr/notice.pdf", "공식 정정 확인");
    }

    private Schedule schedule(Long complexId, ApplicationScheduleState state, int day) {
        return schedule(complexId, state, day, day);
    }

    private Schedule schedule(Long complexId, ApplicationScheduleState state, int startDay, int endDay) {
        return new Schedule(complexId, "순위 확인", state, "선순위 접수 결과에 따라 진행",
                LocalDate.of(2026, 9, startDay), LocalDate.of(2026, 9, endDay), null, null,
                "https://apply.lh.or.kr/notice.pdf", 6);
    }

    private void assertComplexApplicationPeriod(HousingComplex complex, String applicationStatus,
            int startDay, int endDay, Integer dDay) throws Exception {
        entityManager.flush();
        String startDate = LocalDate.of(2026, 9, startDay).toString();
        String endDate = LocalDate.of(2026, 9, endDay).toString();
        mvc.perform(get("/api/v1/complexes")
                        .param("regionCode", complex.getAddress().getCityCountyDistrictCode())
                        .param("southWestLat", "37").param("southWestLng", "126")
                        .param("northEastLat", "38").param("northEastLng", "128"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].complexId").value(complex.getId()))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.applicationStatus")
                        .value(applicationStatus))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.applicationEndAt").value(endDate))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.dDay").value(dDay));
        mvc.perform(get("/api/v1/complexes/{id}", complex.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentAnnouncements[0].applicationStatus").value(applicationStatus))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].applicationStartAt").value(startDate))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].applicationEndAt").value(endDate))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].dDay").value(dDay));
    }

    private HousingComplex complex(Announcement announcement, String regionCode) {
        HousingComplex complex = HousingComplex.create("공식 일정 단지 " + regionCode, "review:" + regionCode,
                "국민임대", Address.create("주소", "1114010100100010000", "1114010100", "11", regionCode,
                        new BigDecimal("37.5"), new BigDecimal("127.0")),
                100, "LH", LocalDate.of(2020, 1, 1), "개별난방", "아파트", "계단식", true, 80, null, null);
        entityManager.persist(complex);
        entityManager.persist(SupplyRow.create(announcement, complex, null, "review:" + regionCode, 0,
                complex.getName(), "주택형", "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, null, 10));
        entityManager.flush();
        return complex;
    }

    Announcement announcement(String panId) {
        return announcements.saveAndFlush(Announcement.create(
                "review:" + panId, null, null, "공식 공고문 검토", AnnouncementPublicationType.ORIGINAL,
                RentalType.NATIONAL_RENTAL, RecruitmentType.WAITLIST, AgencyCode.LH,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 12, 31),
                "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=" + panId,
                null, 0, null));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-15T02:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}
