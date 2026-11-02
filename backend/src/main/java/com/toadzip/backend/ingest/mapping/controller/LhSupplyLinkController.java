package com.toadzip.backend.ingest.mapping.controller;

import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.View;
import com.toadzip.backend.ingest.mapping.service.LhSupplyLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
