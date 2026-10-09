package com.toadzip.backend.ingest.mapping.controller;

import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ComplexOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.HousingTypeOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.RefineRequest;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
import com.toadzip.backend.ingest.mapping.service.AnnouncementSupplyMatchingService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/ingest/announcement-supply-matches")
public class AnnouncementSupplyMatchingController {
    private final AnnouncementSupplyMatchingService matching;

    @GetMapping("/{identifier}")
    public List<Row> rows(@PathVariable String identifier) {
        return matching.rows(identifier);
    }

    @PostMapping("/{identifier}/refine")
    public MyHomeAnnouncementMappingReport refine(@PathVariable String identifier,
            @Valid @RequestBody RefineRequest request) {
        return matching.refine(identifier, request);
    }

    @GetMapping("/complexes")
    public List<ComplexOption> complexes(@RequestParam String query) {
        return matching.complexes(query);
    }

    @GetMapping("/complexes/{complexId}/housing-types")
    public List<HousingTypeOption> types(@PathVariable long complexId) {
        return matching.housingTypes(complexId);
    }
}
