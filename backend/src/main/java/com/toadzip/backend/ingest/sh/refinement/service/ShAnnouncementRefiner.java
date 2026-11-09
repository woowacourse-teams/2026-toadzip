package com.toadzip.backend.ingest.sh.refinement.service;

import com.toadzip.backend.ingest.refinement.domain.BoardRefinementOutcome;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.refinement.domain.BoardRevisionName;
import com.toadzip.backend.ingest.refinement.service.BoardRevisionLinker;
import com.toadzip.backend.ingest.refinement.service.BoardRevisionLinker.LinkResult;
import com.toadzip.backend.ingest.refinement.service.BoardRefinementAttempt;
import com.toadzip.backend.ingest.refinement.service.SourceAttachmentWriter;
import com.toadzip.backend.ingest.refinement.service.SourceAttachmentWriter.SourceAttachment;
import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSource;
import com.toadzip.backend.ingest.sh.refinement.dto.ShAnnouncementBodyValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SH 원천 하나를 미확정 공고로 정제한다. 식별자는 원천 키(`SH:{게시글 번호}`)다. 본문에서 읽은 접수기간·당첨자 발표일 후보를 원천 값으로
 * 채우고, 공고 단지·공급행은 본문에 원천이 없으므로 만들지 않는다(PDF 보정에 맡긴다). 공개와 새 공고 알림은 하지 않는다. 공고문 PDF는
 * 원천 첨부 식별자로 한 번만 붙이고, 본문·첨부를 못 읽은 원천은 그 부분을 그대로 둔다. 공고명이 정정 표시를 달고 있으면 원공고를 찾아 잇는다.
 */
@Component
@RequiredArgsConstructor
class ShAnnouncementRefiner {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AnnouncementRepository announcements;
    private final SourceAttachmentWriter attachmentWriter;
    private final ShAnnouncementBodyParser bodyParser;
    private final BoardRevisionLinker revisionLinker;

    BoardRefinementOutcome refine(ShAnnouncementSource source, BoardRefinementAttempt attempt) {
        Optional<Announcement> existing = announcements.findBySourceAnnouncementIdentifier(source.getSourceKey())
                .flatMap(found -> announcements.findByIdForUpdate(found.getId()));
        if (existing.isPresent() && existing.get().isAdminDeleted()) {
            return BoardRefinementOutcome.TRASHED;
        }
        BoardRefinementOutcome outcome = BoardRefinementOutcome.CREATED;
        Announcement announcement;
        if (existing.isEmpty()) {
            announcement = announcements.save(Announcement.createUnconfirmed(source.getSourceKey(), source.getTitle(),
                    AgencyCode.SH, source.getRegisteredDate(), source.getOriginalUrl()));
        }
        else {
            announcement = existing.get();
            outcome = BoardRefinementOutcome.UNCHANGED;
        }
        boolean reviewBefore = announcement.isSourceReviewRequired();
        boolean changed = announcement.updateFromAgencySource(source.getTitle(), source.getRegisteredDate(),
                source.getOriginalUrl());
        if (source.getBodyText() != null) {
            ShAnnouncementBodyValues values = bodyParser.parse(source.getBodyText());
            changed |= announcement.observeSourceSchedule(values.applicationStartDate(), values.applicationEndDate(),
                    values.winnerAnnouncementDate());
        }
        if (source.getAttachments() != null) {
            attachmentWriter.write(announcement, attachments(JSON.readTree(source.getAttachments())));
        }
        LinkResult link = revisionLinker.link(announcement, BoardRevisionName::sh);
        attempt.recordLink(link);
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
            files.add(new SourceAttachment(file.path("brdId").asText() + ":" + file.path("fileSeq").asText(),
                    file.path("oriFileNm").asText(), file.path("downloadUrl").asText()));
        }
        return files;
    }

}
