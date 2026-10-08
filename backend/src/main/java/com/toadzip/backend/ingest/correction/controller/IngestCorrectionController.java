package com.toadzip.backend.ingest.correction.controller;

import com.toadzip.backend.ingest.correction.dto.IngestCorrectionDetail;
import com.toadzip.backend.ingest.correction.dto.IngestCorrectionRequest;
import com.toadzip.backend.ingest.correction.dto.IngestWorkspacePage;
import com.toadzip.backend.ingest.correction.service.IngestCorrectionStore;
import com.toadzip.backend.ingest.correction.service.IngestWorkspaceService;
import com.toadzip.backend.ingest.correction.service.IngestCorrectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/ingest/workspace/{domain}")
public class IngestCorrectionController {
    private final IngestWorkspaceService workspace;
    private final IngestCorrectionStore store;
    private final IngestCorrectionService correction;

    @GetMapping
    public IngestWorkspacePage items(@PathVariable String domain,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return workspace.find(domain, status, page, size);
    }

    @GetMapping("/{identifier}")
    public IngestCorrectionDetail detail(@PathVariable String domain, @PathVariable String identifier) {
        return store.detail(domain, identifier);
    }

    @PutMapping("/{identifier}")
    public Map<String, Long> correct(@PathVariable String domain, @PathVariable String identifier,
            @Valid @RequestBody IngestCorrectionRequest request, Principal principal) {
        return Map.of("productId", correction.correct(domain, identifier, request, principal.getName()));
    }
}
