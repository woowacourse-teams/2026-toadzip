package com.toadzip.backend.ingest.mapping.controller;

import com.toadzip.backend.ingest.mapping.dto.ComplexMergePreviewRequest;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergePreviewResponse;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergeRequest;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergeResponse;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergeCandidateResponse;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMergeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/myhome/complex-merges")
public class MyHomeComplexMergeController {

    private final MyHomeComplexMergeService service;

    public MyHomeComplexMergeController(MyHomeComplexMergeService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    public List<ComplexMergeCandidateResponse> candidates(
            @RequestParam(defaultValue = "0") @Min(0) long afterId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.candidates(afterId, size);
    }

    @PostMapping("/preview")
    public ComplexMergePreviewResponse preview(@Valid @RequestBody ComplexMergePreviewRequest request) {
        return service.preview(request);
    }

    @PostMapping
    public ComplexMergeResponse merge(@Valid @RequestBody ComplexMergeRequest request, Principal principal) {
        return service.merge(request, principal.getName());
    }

    @GetMapping("/{id}")
    public ComplexMergeResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/revert")
    public ComplexMergeResponse revert(@PathVariable UUID id, Principal principal) {
        return service.revert(id, principal.getName());
    }
}
