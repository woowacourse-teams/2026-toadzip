package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementEnrichmentMapper;
import com.toadzip.backend.ingest.lh.refinement.service.LhScheduleAttachmentWriter;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VerifiedAnnouncementEnrichmentIntegrationTest {

    @Autowired private AnnouncementRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private LhScheduleAttachmentWriter writer;

    @Test
    void LH_보강_값을_써도_확인한_일정과_정정관계를_유지한다() {
        Announcement previous = announcement("previous");
        Announcement reviewed = announcement("correction");
        reviewed.confirmLhRevision(previous, "2015122300020681", "2015122300020856",
                "https://apply.lh.or.kr/notice.pdf", "공식 정정 확인");
        LocalDate confirmed = LocalDate.of(2026, 9, 15);
        reviewed.confirmApplicationPeriod(confirmed, confirmed);
        entityManager.persist(AnnouncementApplicationSchedule.verified(reviewed, null, "1순위",
                ApplicationScheduleState.CONFIRMED, null, confirmed, confirmed, null, null,
                "https://apply.lh.or.kr/notice.pdf", 6));
        entityManager.flush();
        entityManager.clear();

        Announcement stored = repository.findById(reviewed.getId()).orElseThrow();
        stored.enrichFromLh("2015122300020856", "자동 원천 문구", null);
        writer.write(stored, new LhAnnouncementEnrichmentData(
                "2015122300020856", "자동 원천 문구", null, List.of(), List.of(), List.of(), false), null);
        entityManager.flush();
        entityManager.clear();

        stored = repository.findById(reviewed.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AnnouncementPublicationType.CORRECTION);
        assertThat(stored.getPreviousAnnouncement().getId()).isEqualTo(previous.getId());
        assertThat(stored.getCorrectionCancellationReason()).isEqualTo("공식 정정 확인");
        assertThat(stored.isApplicationScheduleReviewed()).isTrue();
        assertThat(stored.getApplicationEndDate()).isEqualTo(confirmed);
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
        var start = LocalDateTime.of(2026, 10, 20, 9, 0);
        var end = start.plusDays(1);
        var event = AnnouncementSchedule.createFromSource(
                announcement, "LH:" + pan + ":event", ScheduleType.APPLICATION,
                "접수", start, end, 0);
        var attachment = AnnouncementAttachment.createFromSource(
                announcement, "LH:" + pan + ":file", "공고.pdf",
                AttachmentType.ANNOUNCEMENT, "https://example.com/old.pdf", 0);
        event.reviseByAdmin(event.getScheduleType(), "공식 확인 접수", start, end);
        attachment.reviseByAdmin("공식 확인.pdf", attachment.getFileType(), "https://example.com/confirmed.pdf");
        entityManager.persist(event); entityManager.persist(attachment); entityManager.flush();
        writer.write(announcement, new LhAnnouncementEnrichmentData(pan, null, null,
                List.of(new LhAnnouncementEnrichmentMapper.LhScheduleData("LH:" + pan + ":event",
                        event.getScheduleType(), "자동 변경 접수", start.plusDays(1), end.plusDays(1), null)),
                List.of(new LhAnnouncementEnrichmentMapper.LhAttachmentData("LH:" + pan + ":file",
                        "자동 변경.pdf", attachment.getFileType(), "https://example.com/changed.pdf")), List.of(), false),
                null);
        entityManager.flush();
        assertThat(event.getName()).isEqualTo("공식 확인 접수");
        assertThat(attachment.getFileName()).isEqualTo("공식 확인.pdf");
        writer.write(announcement, new LhAnnouncementEnrichmentData(pan, null, null,
                List.of(new LhAnnouncementEnrichmentMapper.LhScheduleData("LH:" + pan + ":replacement",
                        event.getScheduleType(), "다른 접수", start, end, null)),
                List.of(new LhAnnouncementEnrichmentMapper.LhAttachmentData("LH:" + pan + ":replacement",
                        "다른 자료.pdf", attachment.getFileType(), "https://example.com/other.pdf")), List.of(), false),
                null);
        entityManager.flush(); entityManager.clear();
        assertThat(entityManager.find(AnnouncementSchedule.class, event.getId()))
                .isNotNull().extracting(AnnouncementSchedule::getName).isEqualTo("공식 확인 접수");
        assertThat(entityManager.find(AnnouncementAttachment.class, attachment.getId()))
                .isNotNull().extracting(AnnouncementAttachment::getFileName).isEqualTo("공식 확인.pdf");
    }

    @Test
    void 같은_LH_일정과_첨부를_다시_써도_중복해_만들지_않는다() {
        var announcement = announcement("repeat");
        var data = scheduleAndAttachment("repeat-pan");

        var first = writer.write(announcement, data, null);
        var second = writer.write(announcement, data, null);
        entityManager.flush();

        assertThat(first.createdSchedules()).isOne();
        assertThat(first.createdAttachments()).isOne();
        assertThat(second.createdSchedules()).isZero();
        assertThat(second.createdAttachments()).isZero();
        assertThat(schedulesOf(announcement)).isOne();
        assertThat(attachmentsOf(announcement)).isOne();
    }

    @Test
    void LH_상세가_일정과_첨부를_주지_않으면_마지막_값을_남긴다() {
        var announcement = announcement("empty");
        writer.write(announcement, scheduleAndAttachment("empty-pan"), null);
        entityManager.flush();

        writer.write(announcement, new LhAnnouncementEnrichmentData("empty-pan", null, null,
                List.of(), List.of(), List.of(), false), null);
        entityManager.flush();

        assertThat(schedulesOf(announcement)).isOne();
        assertThat(attachmentsOf(announcement)).isOne();
    }

    private LhAnnouncementEnrichmentData scheduleAndAttachment(String pan) {
        var start = LocalDateTime.of(2026, 10, 20, 9, 0);
        return new LhAnnouncementEnrichmentData(pan, null, null,
                List.of(new LhAnnouncementEnrichmentMapper.LhScheduleData("LH:" + pan + ":event",
                        ScheduleType.APPLICATION, "접수", start, start.plusDays(1), null)),
                List.of(new LhAnnouncementEnrichmentMapper.LhAttachmentData("LH:" + pan + ":file",
                        "공고.pdf", AttachmentType.ANNOUNCEMENT, "https://example.com/notice.pdf")),
                List.of(), false);
    }

    private long schedulesOf(Announcement announcement) {
        return entityManager.createQuery("SELECT count(s) FROM AnnouncementSchedule s WHERE s.announcement = :a",
                Long.class).setParameter("a", announcement).getSingleResult();
    }

    private long attachmentsOf(Announcement announcement) {
        return entityManager.createQuery("SELECT count(a) FROM AnnouncementAttachment a WHERE a.announcement = :a",
                Long.class).setParameter("a", announcement).getSingleResult();
    }
}
