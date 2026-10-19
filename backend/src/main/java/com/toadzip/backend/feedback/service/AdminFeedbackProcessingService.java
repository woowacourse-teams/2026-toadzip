package com.toadzip.backend.feedback.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.dto.AdminChangeResponse;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.feedback.dto.FeedbackProcessingRequest;
import com.toadzip.backend.feedback.dto.FeedbackSummary;
import com.toadzip.backend.feedback.repository.FeedbackRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasRole('ADMIN')")
public class AdminFeedbackProcessingService {
    private final FeedbackRepository feedbacks;
    private final HousingComplexRepository complexes;
    private final AnnouncementRepository announcements;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;
    private final Clock clock;

    public FeedbackSummary find(long id) {
        return FeedbackSummary.from(feedbacks.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "의견을 찾을 수 없습니다.")));
    }

    public List<AdminChangeResponse> history(long id) {
        find(id);
        return changes.findByResourceTypeAndResourceIdOrderByIdDesc("FEEDBACK", id, PageRequest.of(0, 100))
                .stream().map(AdminChangeResponse::from).toList();
    }

    @Transactional
    public FeedbackSummary process(long id, FeedbackProcessingRequest request, String actor) {
        var feedback = feedbacks.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "의견을 찾을 수 없습니다."));
        if (feedback.getVersion() != request.version()) {
            throw new AdminDataConflictException("다른 관리자가 처리 내용을 변경했습니다. 입력을 보관하고 최신 의견을 확인하세요.");
        }
        verifyRelated(request);
        var before = FeedbackSummary.from(feedback);
        feedback.process(request.status(), request.note().strip(),
                request.relatedResource(), request.relatedId(), actor, clock.instant());
        feedbacks.flush();
        var after = FeedbackSummary.from(feedback);
        changes.save(new AdminDataChange("FEEDBACK", id, "PROCESS_FEEDBACK", actor,
                json.writeValueAsString(before), json.writeValueAsString(after)));
        return after;
    }

    private void verifyRelated(FeedbackProcessingRequest request) {
        if (request.relatedId() == null) { return; }
        boolean valid = switch (request.relatedResource()) {
            case "COMPLEX" -> complexes.existsById(request.relatedId());
            case "ANNOUNCEMENT" -> announcements.existsById(request.relatedId());
            default -> false;
        };
        if (!valid) { throw new IllegalArgumentException("관련 단지 또는 공고를 찾을 수 없습니다."); }
    }
}
