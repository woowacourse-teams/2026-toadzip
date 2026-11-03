package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.CorrectionDraftContent;
import com.toadzip.backend.announcement.domain.StoredCorrectionDraft;
import com.toadzip.backend.announcement.dto.correction.CorrectionDraft;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.CorrectionDraftConflictException;
import com.toadzip.backend.announcement.exception.CorrectionDraftTooLargeException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.CorrectionDraftRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 관리자가 PDF 보정 화면에서 진행 중인 작업을 관리자별·공고별로 임시저장한다.
 * 초안은 공고·공급행 같은 공개 데이터를 바꾸지 않고, 적용 뒤 삭제는 화면이 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class CorrectionDraftService {
    public static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    private final AnnouncementRepository announcements;
    private final CorrectionDraftRepository drafts;
    private final AnnouncementJsonCorrectionService corrections;
    private final ObjectMapper json;
    private final Clock clock;

    /** 이 관리자의 초안. 없으면 null이다. stale은 초안을 만든 뒤 공고의 보정 작업 공간이 바뀌었는지다. */
    public CorrectionDraft.View find(long announcementId, String admin) {
        requireAnnouncement(announcementId);
        return drafts.find(announcementId, admin)
                .map(draft -> CorrectionDraft.View.of(draft, corrections.workspace(announcementId).snapshotToken()))
                .orElse(null);
    }

    @Transactional
    public CorrectionDraft.Saved save(long announcementId, String admin, CorrectionDraft.SaveRequest request) {
        requireAnnouncement(announcementId);
        var content = new CorrectionDraftContent(payloadOf(request), request.baseSnapshotToken(), request.pdfSha256(),
                request.extractionId());
        var saved = write(announcementId, admin, request.expectedRevision(), content);
        return CorrectionDraft.Saved.of(saved.orElseThrow(() -> conflict(announcementId, admin, request.expectedRevision())));
    }

    /** 초안이 이미 없으면 지운 것으로 본다. 다른 revision이 저장되어 있으면 지우지 않는다. */
    @Transactional
    public void delete(long announcementId, String admin, long expectedRevision) {
        requireAnnouncement(announcementId);
        if (drafts.deleteIfRevision(announcementId, admin, expectedRevision)) {
            return;
        }
        if (drafts.find(announcementId, admin).isPresent()) {
            throw conflict(announcementId, admin, expectedRevision);
        }
    }

    private Optional<StoredCorrectionDraft> write(long announcementId, String admin, Long expectedRevision,
            CorrectionDraftContent content) {
        if (expectedRevision == null) {
            return drafts.insertIfAbsent(announcementId, admin, content, clock.instant());
        }
        return drafts.updateIfRevision(announcementId, admin, expectedRevision, content, clock.instant());
    }

    private String payloadOf(CorrectionDraft.SaveRequest request) {
        String payload = json.writeValueAsString(request.payload());
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new CorrectionDraftTooLargeException();
        }
        return payload;
    }

    private CorrectionDraftConflictException conflict(long announcementId, String admin, Long expectedRevision) {
        var current = drafts.find(announcementId, admin);
        var currentRevision = current.map(StoredCorrectionDraft::revision).orElse(null);
        log.info("event=announcement.correction_draft.conflict announcementId={} expectedRevision={} currentRevision={}",
                announcementId, expectedRevision, currentRevision);
        return new CorrectionDraftConflictException(currentRevision,
                current.map(StoredCorrectionDraft::updatedAt).orElse(null));
    }

    private void requireAnnouncement(long announcementId) {
        if (!announcements.existsById(announcementId)) {
            throw new AnnouncementNotFoundException();
        }
    }
}
