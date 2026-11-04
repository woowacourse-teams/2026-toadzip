package com.toadzip.backend.ingest.refinement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 기관 게시판 원천의 첨부 중 PDF만 공고문으로 붙인다. 원천 첨부 식별자로 한 번만 붙이고, 이미 붙은 첨부는 원천 값으로 맞춘다(관리자가
 * 고친 첨부는 그대로). 원천에서 빠진 첨부는 지우지 않는다. PDF 보정이 공고문을 PDF로 읽기 때문에 다른 형식은 붙이지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SourceAttachmentWriter {

    private final AnnouncementAttachmentRepository attachments;

    public void write(Announcement announcement, List<SourceAttachment> sourceAttachments) {
        Map<String, AnnouncementAttachment> stored = attachments.findAllByAnnouncement(announcement).stream()
                .filter(attachment -> attachment.getSourceAttachmentIdentifier() != null)
                .collect(Collectors.toMap(AnnouncementAttachment::getSourceAttachmentIdentifier, Function.identity(),
                        (first, second) -> first));
        int order = 0;
        for (SourceAttachment file : sourceAttachments) {
            if (!file.fileName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                continue;
            }
            AnnouncementAttachment found = stored.get(file.identifier());
            if (found == null) {
                attachments.save(AnnouncementAttachment.createFromSource(announcement, file.identifier(),
                        file.fileName(), AttachmentType.ANNOUNCEMENT, file.url(), order));
            }
            else {
                found.updateFromSource(file.fileName(), AttachmentType.ANNOUNCEMENT, file.url(), order);
            }
            order++;
        }
    }

    public record SourceAttachment(String identifier, String fileName, String url) {
    }
}
