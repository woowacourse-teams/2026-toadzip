package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.admin.controller.AdminManagementApi;
import com.toadzip.backend.announcement.dto.correction.CorrectionDraft;
import com.toadzip.backend.announcement.service.CorrectionDraftService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 관리자 본인의 PDF 보정 초안만 읽고 쓴다. 공개 데이터는 바꾸지 않아 수집 실행 소유권을 잡지 않는다. */
@AdminManagementApi
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/announcements/{id}/correction-draft")
public class CorrectionDraftController {
    private final CorrectionDraftService service;

    @GetMapping
    public ApiResponse<CorrectionDraft.View> find(@PathVariable long id, Principal principal) {
        return new ApiResponse<>(service.find(id, principal.getName()));
    }

    @PutMapping
    public ApiResponse<CorrectionDraft.Saved> save(@PathVariable long id,
            @Valid @RequestBody CorrectionDraft.SaveRequest request, Principal principal) {
        return new ApiResponse<>(service.save(id, principal.getName(), request));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @RequestParam @Positive long expectedRevision, Principal principal) {
        service.delete(id, principal.getName(), expectedRevision);
    }
}
