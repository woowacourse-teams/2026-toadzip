package com.toadzip.backend.ingest.mapping.controller;

import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ComplexOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.HousingTypeOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.RefineRequest;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ReleaseRequest;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
import com.toadzip.backend.ingest.mapping.service.AnnouncementSupplyMatchingService;
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
            @Valid @RequestBody RefineRequest request, Principal principal) {
        return matching.refine(identifier, request, principal.getName());
    }

    @PostMapping("/{identifier}/rerun")
    public MyHomeAnnouncementMappingReport rerun(@PathVariable String identifier) {
        return matching.rerun(identifier);
    }

    @PostMapping("/{identifier}/release")
    public List<Row> release(@PathVariable String identifier,
            @Valid @RequestBody ReleaseRequest request, Principal principal) {
        return matching.release(identifier, request, principal.getName());
    }

    @PostMapping("/{identifier}/exclusion")
    public List<Row> exclude(@PathVariable String identifier,
            @Valid @RequestBody com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ExclusionRequest request,
            Principal principal) {
        return matching.exclude(identifier, request, principal.getName());
    }

    @PostMapping("/{identifier}/split")
    public List<Row> split(@PathVariable String identifier,
            @Valid @RequestBody com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SplitRequest request,
            Principal principal) {
        return matching.split(identifier, request, principal.getName());
    }

    @GetMapping("/complexes")
    public List<ComplexOption> complexes(@RequestParam String query,
            @RequestParam(defaultValue = "") String pnu) {
        return matching.complexes(query, pnu);
    }

    @GetMapping("/complexes/{complexId}/housing-types")
    public List<HousingTypeOption> types(@PathVariable long complexId) {
        return matching.housingTypes(complexId);
    }
}
