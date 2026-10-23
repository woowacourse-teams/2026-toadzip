package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentRejectedException;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VerifiedAnnouncementEnrichmentIntegrationTest {

    @Autowired private AnnouncementRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private LhAnnouncementEnrichmentWriter writer;

    @Test
    void 오래된_공고_객체로_보강해도_확인한_일정과_정정관계를_유지한다() {
        Announcement previous = announcement("previous");
        Announcement detached = announcement("correction");
        entityManager.detach(detached);
        Announcement reviewed = repository.findById(detached.getId()).orElseThrow();
        reviewed.confirmLhRevision(previous, "2015122300020681", "2015122300020856",
                "https://apply.lh.or.kr/notice.pdf", "공식 정정 확인");
        LocalDate confirmed = LocalDate.of(2026, 9, 15);
        reviewed.confirmApplicationPeriod(confirmed, confirmed);
        entityManager.persist(AnnouncementApplicationSchedule.verified(reviewed, null, "1순위",
                ApplicationScheduleState.CONFIRMED, null, confirmed, confirmed, null, null,
                "https://apply.lh.or.kr/notice.pdf", 6));

        writer.write(detached, new LhAnnouncementEnrichmentData(
                "2015122300020856", "자동 원천 문구", null, List.of(), List.of(), List.of()),
                Set.of(), Set.of());
        entityManager.flush();
        entityManager.clear();

        Announcement stored = repository.findById(detached.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AnnouncementPublicationType.CORRECTION);
        assertThat(stored.getPreviousAnnouncement().getId()).isEqualTo(previous.getId());
        assertThat(stored.getCorrectionCancellationReason()).isEqualTo("공식 정정 확인");
        assertThat(stored.isApplicationScheduleReviewed()).isTrue();
        assertThat(stored.getApplicationEndDate()).isEqualTo(confirmed);
    }

    @Test
    void 확인된_정정본의_PAN을_다른_원천으로_보강하지_않는다() {
        Announcement previous = announcement("previous");
        Announcement correction = announcement("correction");
        correction.confirmLhRevision(previous, "2015122300020681", "2015122300020856",
                "https://apply.lh.or.kr/notice.pdf", "공식 정정 확인");

        assertThatThrownBy(() -> writer.write(correction, new LhAnnouncementEnrichmentData(
                "2015122300020681", null, null, List.of(), List.of(), List.of()), Set.of(), Set.of()))
                .isInstanceOf(LhAnnouncementEnrichmentRejectedException.class)
                .hasMessageContaining("확인된 공고의 LH 원천");
        assertThatThrownBy(() -> writer.write(previous, new LhAnnouncementEnrichmentData(
                "2015122300020856", null, null, List.of(), List.of(), List.of()), Set.of(), Set.of()))
                .isInstanceOf(LhAnnouncementEnrichmentRejectedException.class);
        assertThat(previous.getLhPanId()).isEqualTo("2015122300020681");
        assertThat(correction.getLhPanId()).isEqualTo("2015122300020856");
    }

    private Announcement announcement(String identifier) {
        return repository.saveAndFlush(Announcement.create("reviewed:" + identifier, null, null,
                "보강 테스트", AnnouncementPublicationType.ORIGINAL, RentalType.NATIONAL_RENTAL,
                RecruitmentType.WAITLIST, AgencyCode.LH, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 12, 31), LocalDate.of(2026, 12, 31),
                "https://apply.lh.or.kr/notice", null, 0L, null));
    }

    @Test
    void JSON으로_보정한_일정과_첨부는_원천에서_변경되거나_사라져도_유지한다() {
        var announcement = announcement("json-children");
        String pan = "json-pan";
        var start = java.time.LocalDateTime.of(2026, 10, 20, 9, 0);
        var end = start.plusDays(1);
        var event = com.toadzip.backend.announcement.domain.AnnouncementSchedule.createFromSource(
                announcement, "LH:" + pan + ":event", com.toadzip.backend.announcement.domain.ScheduleType.APPLICATION,
                "접수", start, end, 0);
        var attachment = com.toadzip.backend.announcement.domain.AnnouncementAttachment.createFromSource(
                announcement, "LH:" + pan + ":file", "공고.pdf",
                com.toadzip.backend.announcement.domain.AttachmentType.ANNOUNCEMENT, "https://example.com/old.pdf", 0);
        event.reviseByAdmin(event.getScheduleType(), "공식 확인 접수", start, end);
        attachment.reviseByAdmin("공식 확인.pdf", attachment.getFileType(), "https://example.com/confirmed.pdf");
        entityManager.persist(event); entityManager.persist(attachment); entityManager.flush();
        writer.write(announcement, new LhAnnouncementEnrichmentData(pan, null, null,
                List.of(new LhAnnouncementEnrichmentMapper.LhScheduleData("LH:" + pan + ":event",
                        event.getScheduleType(), "자동 변경 접수", start.plusDays(1), end.plusDays(1), null)),
                List.of(new LhAnnouncementEnrichmentMapper.LhAttachmentData("LH:" + pan + ":file",
                        "자동 변경.pdf", attachment.getFileType(), "https://example.com/changed.pdf")), List.of()),
                Set.of(), Set.of());
        entityManager.flush();
        assertThat(event.getName()).isEqualTo("공식 확인 접수");
        assertThat(attachment.getFileName()).isEqualTo("공식 확인.pdf");
        writer.write(announcement, new LhAnnouncementEnrichmentData(pan, null, null,
                List.of(new LhAnnouncementEnrichmentMapper.LhScheduleData("LH:" + pan + ":replacement",
                        event.getScheduleType(), "다른 접수", start, end, null)),
                List.of(new LhAnnouncementEnrichmentMapper.LhAttachmentData("LH:" + pan + ":replacement",
                        "다른 자료.pdf", attachment.getFileType(), "https://example.com/other.pdf")), List.of()),
                Set.of(), Set.of());
        entityManager.flush(); entityManager.clear();
        assertThat(entityManager.find(com.toadzip.backend.announcement.domain.AnnouncementSchedule.class,
                event.getId()).getName()).isEqualTo("공식 확인 접수");
        assertThat(entityManager.find(com.toadzip.backend.announcement.domain.AnnouncementAttachment.class,
                attachment.getId()).getFileName()).isEqualTo("공식 확인.pdf");
    }
}
