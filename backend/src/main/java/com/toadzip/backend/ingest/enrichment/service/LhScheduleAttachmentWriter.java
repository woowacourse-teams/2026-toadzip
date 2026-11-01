package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAttachmentData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhScheduleData;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LH 상세 원천의 일정·첨부를 공고에 맞춘다. 원천 식별자가 같으면 고치고 없으면 만든다. 원천에 남지 않은 보강의
 * 값은 지우되, 원천이 비면 같은 LH 공고의 마지막 정상값을 남기고 연결이 바뀐 이전 LH 공고의 값만 지운다.
 */
@Component
@RequiredArgsConstructor
public class LhScheduleAttachmentWriter {

    private final AnnouncementScheduleRepository scheduleRepository;
    private final AnnouncementAttachmentRepository attachmentRepository;

    public Result write(Announcement announcement, LhAnnouncementEnrichmentData data, String previousPanId) {
        Count schedules = writeSchedules(announcement, data.schedules(),
                replacedPanIds(data.schedules(), data.panId(), previousPanId));
        Count attachments = writeAttachments(announcement, data.attachments(),
                replacedPanIds(data.attachments(), data.panId(), previousPanId));
        return new Result(schedules.created(), schedules.updated(), attachments.created(), attachments.updated());
    }

    /** 공고가 LH 소유에서 벗어나면 보강의 일정·첨부를 모두 지운다. */
    void release(Announcement announcement) {
        scheduleRepository.deleteAll(scheduleRepository.findAllByAnnouncement(announcement).stream()
                .filter(schedule -> LhOwnedValues.owned(
                        schedule.getSourceScheduleIdentifier(), schedule.isAdminModified()))
                .toList());
        attachmentRepository.deleteAll(attachmentRepository.findAllByAnnouncement(announcement).stream()
                .filter(attachment -> LhOwnedValues.owned(
                        attachment.getSourceAttachmentIdentifier(), attachment.isAdminModified()))
                .toList());
    }

    /** 원천에 남지 않은 보강의 값을 지울 LH 공고들이다. */
    private static Set<String> replacedPanIds(Collection<?> sources, String panId, String previousPanId) {
        Set<String> panIds = new HashSet<>();
        if (!sources.isEmpty()) {
            panIds.add(panId);
        }
        if (previousPanId != null && (!sources.isEmpty() || !previousPanId.equals(panId))) {
            panIds.add(previousPanId);
        }
        return panIds;
    }

    private Count writeSchedules(Announcement announcement, List<LhScheduleData> sources, Set<String> replacedPanIds) {
        List<AnnouncementSchedule> storedSchedules = scheduleRepository.findAllByAnnouncement(announcement);
        Map<String, AnnouncementSchedule> stored = new HashMap<>();
        for (AnnouncementSchedule schedule : storedSchedules) {
            if (schedule.getSourceScheduleIdentifier() != null) {
                stored.put(schedule.getSourceScheduleIdentifier(), schedule);
            }
        }
        Set<String> retained = new HashSet<>();
        int created = 0;
        int updated = 0;
        int order = 1;
        for (LhScheduleData source : sources) {
            retained.add(source.sourceIdentifier());
            AnnouncementSchedule schedule = stored.get(source.sourceIdentifier());
            if (schedule == null) {
                scheduleRepository.save(AnnouncementSchedule.createFromSource(
                        announcement, source.sourceIdentifier(), source.type(), source.name(),
                        source.startAt(), source.endAt(), order++, source.complexName()
                ));
                created++;
                continue;
            }
            if (schedule.updateFromSource(
                    source.type(), source.name(), source.startAt(), source.endAt(), order++, source.complexName())) {
                updated++;
            }
        }
        scheduleRepository.deleteAll(storedSchedules.stream()
                .filter(schedule -> stale(schedule.getSourceScheduleIdentifier(), schedule.isAdminModified(),
                        replacedPanIds, retained))
                .toList());
        return new Count(created, updated);
    }

    private Count writeAttachments(Announcement announcement, List<LhAttachmentData> sources,
            Set<String> replacedPanIds) {
        List<AnnouncementAttachment> storedAttachments = attachmentRepository.findAllByAnnouncement(announcement);
        Map<String, AnnouncementAttachment> stored = new HashMap<>();
        for (AnnouncementAttachment attachment : storedAttachments) {
            if (attachment.getSourceAttachmentIdentifier() != null) {
                stored.put(attachment.getSourceAttachmentIdentifier(), attachment);
            }
        }
        Set<String> retained = new HashSet<>();
        int created = 0;
        int updated = 0;
        int order = 1;
        for (LhAttachmentData source : sources) {
            retained.add(source.sourceIdentifier());
            AnnouncementAttachment attachment = stored.get(source.sourceIdentifier());
            if (attachment == null) {
                attachmentRepository.save(AnnouncementAttachment.createFromSource(
                        announcement, source.sourceIdentifier(), source.name(), source.type(), source.url(), order++
                ));
                created++;
                continue;
            }
            if (attachment.updateFromSource(source.name(), source.type(), source.url(), order++)) {
                updated++;
            }
        }
        attachmentRepository.deleteAll(storedAttachments.stream()
                .filter(attachment -> stale(attachment.getSourceAttachmentIdentifier(), attachment.isAdminModified(),
                        replacedPanIds, retained))
                .toList());
        return new Count(created, updated);
    }

    private static boolean stale(String sourceIdentifier, boolean adminModified, Set<String> replacedPanIds,
            Set<String> retained) {
        return LhOwnedValues.ownedFrom(sourceIdentifier, adminModified, replacedPanIds)
                && !retained.contains(sourceIdentifier);
    }

    private record Count(int created, int updated) {
    }

    public record Result(int createdSchedules, int updatedSchedules, int createdAttachments, int updatedAttachments) {
    }
}
