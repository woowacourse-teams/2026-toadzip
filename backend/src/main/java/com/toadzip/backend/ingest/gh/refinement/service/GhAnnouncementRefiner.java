package com.toadzip.backend.ingest.gh.refinement.service;

import com.toadzip.backend.ingest.refinement.domain.BoardRefinementOutcome;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementSource;
import com.toadzip.backend.ingest.refinement.domain.BoardRevisionName;
import com.toadzip.backend.ingest.refinement.service.BoardRevisionLinker;
import com.toadzip.backend.ingest.refinement.service.BoardRevisionLinker.LinkResult;
import com.toadzip.backend.ingest.refinement.service.BoardRefinementAttempt;
import com.toadzip.backend.ingest.refinement.service.SourceAttachmentWriter;
import com.toadzip.backend.ingest.refinement.service.SourceAttachmentWriter.SourceAttachment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final SourceAttachmentWriter attachmentWriter;
    private final GhAnnouncementComplexLinker complexLinker;
    private final GhSupplyRowWriter supplyRowWriter;
    private final BoardRevisionLinker revisionLinker;

    BoardRefinementOutcome refine(GhAnnouncementSource source, GhComplexIndex index, BoardRefinementAttempt attempt) {
        Optional<Announcement> existing = announcements.findBySourceAnnouncementIdentifier(source.getSourceKey())
                .flatMap(found -> announcements.findByIdForUpdate(found.getId()));
        if (existing.isPresent() && existing.get().isAdminDeleted()) {
            return BoardRefinementOutcome.TRASHED;
        }
        BoardRefinementOutcome outcome = BoardRefinementOutcome.CREATED;
        Announcement announcement;
        if (existing.isEmpty()) {
            announcement = announcements.save(Announcement.createUnconfirmed(source.getSourceKey(), source.getTitle(),
                    AgencyCode.GH, source.getPostedDate(), source.getOriginalUrl()));
        }
        else {
            announcement = existing.get();
            outcome = BoardRefinementOutcome.UNCHANGED;
        }
        Optional<RentalType> supplyType = GhComplexIndex.rentalType(source.getSupplyType());
        boolean reviewBefore = announcement.isSourceReviewRequired();
        boolean changed = announcement.updateFromAgencySource(source.getTitle(), source.getPostedDate(),
                source.getOriginalUrl());
        changed |= announcement.observeSourceSupplyType(supplyType.orElse(null));
        LinkResult link = revisionLinker.link(announcement, BoardRevisionName::gh);
        attempt.recordLink(link);
        if (source.getDistricts() != null) {
            JsonNode districts = JSON.readTree(source.getDistricts());
            Map<String, AnnouncementComplex> complexes = complexLinker.observe(announcement, districts, supplyType, index);
            if (supplyRowWriter.write(announcement, source.getTitle(), districts, complexes)) {
                attempt.requireSupplyRecheck();
            }
        }
        if (source.getAttachments() != null) {
            attachmentWriter.write(announcement, attachments(JSON.readTree(source.getAttachments())));
        }
        return outcome(outcome, changed || link == LinkResult.LINKED, !reviewBefore && announcement.isSourceReviewRequired());
    }

    private static BoardRefinementOutcome outcome(BoardRefinementOutcome base, boolean changed, boolean reviewRequired) {
        if (base == BoardRefinementOutcome.CREATED) {
            return base;
        }
        if (changed) {
            return BoardRefinementOutcome.UPDATED;
        }
        if (reviewRequired) {
            return BoardRefinementOutcome.REVIEW_REQUIRED;
        }
        return BoardRefinementOutcome.UNCHANGED;
    }

    private static List<SourceAttachment> attachments(JsonNode sourceAttachments) {
        List<SourceAttachment> files = new ArrayList<>();
        for (JsonNode file : sourceAttachments) {
            files.add(new SourceAttachment(file.path("atchFileSn").asText() + ":" + file.path("atchFileDtlSn").asText(),
                    file.path("fileName").asText(), file.path("downloadUrl").asText()));
        }
        return files;
    }

}
