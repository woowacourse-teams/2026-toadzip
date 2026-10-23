package com.toadzip.backend.ingest.mapping.controller;

import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.ReleaseRequest;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.SaveRequest;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.View;
import com.toadzip.backend.ingest.mapping.service.LhSupplyLinkService;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/ingest/announcement-supply-matches/{identifier}/lh-links")
public class LhSupplyLinkController {
    private final LhSupplyLinkService links;

    @GetMapping
    public View view(@PathVariable String identifier) {
        return links.view(identifier);
    }

    @PostMapping
    public View save(@PathVariable String identifier, @Valid @RequestBody SaveRequest request, Principal principal) {
        return links.save(identifier, request, principal.getName());
    }

    @PostMapping("/release")
    public View release(@PathVariable String identifier, @Valid @RequestBody ReleaseRequest request,
            Principal principal) {
        return links.release(identifier, request, principal.getName());
    }
}
