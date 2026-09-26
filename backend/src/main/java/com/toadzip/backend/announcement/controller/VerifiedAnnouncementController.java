package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.request.VerifiedApplicationSchedulesRequest;
import com.toadzip.backend.announcement.dto.request.VerifiedLhRevisionRequest;
import com.toadzip.backend.announcement.service.VerifiedApplicationScheduleService;
import com.toadzip.backend.announcement.service.VerifiedLhRevisionService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/announcements")
public class VerifiedAnnouncementController {

    private final VerifiedApplicationScheduleService scheduleService;
    private final VerifiedLhRevisionService revisionService;
    private final IngestExecutionOwnershipService ownershipService;

    public VerifiedAnnouncementController(VerifiedApplicationScheduleService scheduleService,
            VerifiedLhRevisionService revisionService, IngestExecutionOwnershipService ownershipService) {
        this.scheduleService = scheduleService;
        this.revisionService = revisionService;
        this.ownershipService = ownershipService;
    }

    @Operation(summary = "확인한 접수 일정 전체 교체", description = "공고문 근거를 보존합니다. 검색 상태는 서울 날짜 기준입니다.")
    @PutMapping("/{announcementId}/application-schedules")
    public ResponseEntity<Void> replaceSchedules(@PathVariable long announcementId,
            @Valid @RequestBody VerifiedApplicationSchedulesRequest request) {
        try (var ignored = ownershipService.acquire()) {
            scheduleService.replace(announcementId, request);
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "확인한 LH 정정 공고 연결", description = "기존 두 공고의 PAN과 공식 근거로 개정 관계를 연결합니다.")
    @PutMapping("/{announcementId}/revision")
    public ResponseEntity<Void> linkRevision(@PathVariable long announcementId,
            @Valid @RequestBody VerifiedLhRevisionRequest request) {
        try (var ignored = ownershipService.acquire()) {
            revisionService.link(announcementId, request);
        }
        return ResponseEntity.noContent().build();
    }
}
