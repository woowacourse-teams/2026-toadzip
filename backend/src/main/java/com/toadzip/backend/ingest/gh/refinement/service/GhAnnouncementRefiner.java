package com.toadzip.backend.ingest.gh.refinement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementSource;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GH 원천 하나를 미확정 공고로 정제한다. 식별자는 원천 키(`GH:{공고 번호}`)다. 공개는 관리자가 PDF 보정에서 요청할 때만 하므로
 * 정제는 공개하지 않고 새 공고 알림도 쌓지 않는다. 공고문 PDF는 원천 첨부 식별자로 한 번만 붙이고, 첨부를 못 읽은 원천은 이미 붙인
 * 첨부를 그대로 둔다. 지구는 공고 단지로 맞추고 GH 제품 단지를 미리 고르며 주택형마다 공급행 틀을 만든다. 지구를 못 읽은 원천은
 * 공고 단지와 공급행을 그대로 둔다.
 */
@Component
@RequiredArgsConstructor
class GhAnnouncementRefiner {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AnnouncementRepository announcements;
    private final AnnouncementAttachmentRepository attachments;
    private final GhAnnouncementComplexLinker complexLinker;
    private final GhSupplyRowWriter supplyRowWriter;

    Outcome refine(GhAnnouncementSource source, GhComplexIndex index) {
        Optional<Announcement> existing = announcements.findBySourceAnnouncementIdentifier(source.getSourceKey())
                .flatMap(found -> announcements.findByIdForUpdate(found.getId()));
        if (existing.isPresent() && existing.get().isAdminDeleted()) {
            return Outcome.TRASHED;
        }
        Outcome outcome = Outcome.CREATED;
        Announcement announcement;
        if (existing.isEmpty()) {
            announcement = announcements.save(Announcement.createUnconfirmed(source.getSourceKey(), source.getTitle(),
                    AgencyCode.GH, source.getPostedDate(), source.getOriginalUrl()));
        }
        else {
            announcement = existing.get();
            outcome = update(announcement, source);
        }
        if (source.getDistricts() != null) {
            JsonNode districts = JSON.readTree(source.getDistricts());
            Map<String, AnnouncementComplex> complexes = complexLinker.observe(announcement, districts,
                    GhComplexIndex.rentalType(source.getSupplyType()), index);
            supplyRowWriter.write(announcement, source.getTitle(), districts, complexes);
        }
        if (source.getAttachments() != null) {
            writeAttachments(announcement, JSON.readTree(source.getAttachments()));
        }
        return outcome;
    }

    private Outcome update(Announcement announcement, GhAnnouncementSource source) {
        boolean reviewBefore = announcement.isSourceReviewRequired();
        if (announcement.updateFromGh(source.getTitle(), source.getPostedDate(), source.getOriginalUrl())) {
            return Outcome.UPDATED;
        }
        if (!reviewBefore && announcement.isSourceReviewRequired()) {
            return Outcome.REVIEW_REQUIRED;
        }
        return Outcome.UNCHANGED;
    }

    private void writeAttachments(Announcement announcement, JsonNode sourceAttachments) {
        Map<String, AnnouncementAttachment> stored = attachments.findAllByAnnouncement(announcement).stream()
                .filter(attachment -> attachment.getSourceAttachmentIdentifier() != null)
                .collect(Collectors.toMap(AnnouncementAttachment::getSourceAttachmentIdentifier, Function.identity(),
                        (first, second) -> first));
        int order = 0;
        for (JsonNode file : sourceAttachments) {
            String fileName = file.path("fileName").asText();
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                continue;
            }
            String identifier = file.path("atchFileSn").asText() + ":" + file.path("atchFileDtlSn").asText();
            String url = file.path("downloadUrl").asText();
            AnnouncementAttachment found = stored.get(identifier);
            if (found == null) {
                attachments.save(AnnouncementAttachment.createFromSource(announcement, identifier, fileName,
                        AttachmentType.ANNOUNCEMENT, url, order));
            }
            else {
                found.updateFromSource(fileName, AttachmentType.ANNOUNCEMENT, url, order);
            }
            order++;
        }
    }

    enum Outcome {
        CREATED, UPDATED, UNCHANGED, REVIEW_REQUIRED, TRASHED
    }
}
