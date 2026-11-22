package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.admin.controller.AdminManagementApi;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.service.SupplyCorrectionService;
import com.toadzip.backend.announcement.service.SupplyReviewService;
import com.toadzip.backend.announcement.dto.correction.SupplyReview;
import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.validation.Valid;
import java.util.List;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@AdminManagementApi
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/announcements/{id}/supply-corrections")
public class SupplyCorrectionController {
    private final SupplyCorrectionService service;
    private final IngestExecutionOwnershipService ownership;
    private final SupplyReviewService reviews;

    @GetMapping
    public ApiResponse<SupplyCorrection.Workspace> workspace(@PathVariable long id) {
        return new ApiResponse<>(service.workspace(id));
    }

    @GetMapping("/reviews")
    public ApiResponse<SupplyReview.Overview> reviews(@PathVariable long id) {
        return new ApiResponse<>(reviews.overview(id));
    }

    @PostMapping("/reviews")
    public ApiResponse<SupplyReview.Overview> review(@PathVariable long id,
            @Valid @RequestBody SupplyReview.Request request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(reviews.record(id, request, principal.getName()));
        }
    }

    @PostMapping("/preview")
    public ApiResponse<SupplyCorrection.Preview> preview(@PathVariable long id,
            @Valid @RequestBody SupplyCorrection.Request request) {
        return new ApiResponse<>(service.preview(id, request));
    }

    /** PDF 보정 단지 정하기에서 공고 단지 주소와 같은 곳의 기존 단지. 주소는 한두 개(공고문 개요 주소·원천 도로명주소)다. */
    @GetMapping("/same-address-complexes")
    public ApiResponse<List<SupplyCorrection.SameAddressComplex>> sameAddressComplexes(@PathVariable long id,
            @RequestParam("address") List<String> addresses, @RequestParam(required = false) RentalType rentalType) {
        if (addresses.size() > 2 || addresses.stream().anyMatch(address -> address.isBlank() || address.length() > 500)) {
            throw new IllegalArgumentException("주소는 500자 이하로 두 개까지 보낼 수 있습니다.");
        }
        return new ApiResponse<>(service.sameAddressComplexes(id, addresses, rentalType));
    }

    /** 공고 단지 하나의 연결 단지를 관리자가 바꾼다. 그 공고 단지의 공급행도 새 단지로 옮긴다. */
    @PostMapping("/announcement-complexes/{announcementComplexId}/link")
    public ApiResponse<SupplyCorrection.Workspace> linkAnnouncementComplex(@PathVariable long id, @PathVariable long announcementComplexId,
            @Valid @RequestBody SupplyCorrection.ComplexLinkRequest request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(service.linkAnnouncementComplex(id, announcementComplexId, request, principal.getName()));
        }
    }

    /** 정본을 확정하지 않은 공고의 기관 준비 데이터를 공고 공급표(정본)로 복사해 확정한다. */
    @PostMapping("/start-from-staging")
    public ApiResponse<SupplyCorrection.Workspace> startFromStaging(@PathVariable long id,
            @Valid @RequestBody SupplyCorrection.StartFromStaging request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(service.startFromStaging(id, request, principal.getName()));
        }
    }

    @PostMapping
    public ApiResponse<SupplyCorrection.Applied> apply(@PathVariable long id,
            @Valid @RequestBody SupplyCorrection.Apply request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(service.apply(id, request, principal.getName()));
        }
    }
}
