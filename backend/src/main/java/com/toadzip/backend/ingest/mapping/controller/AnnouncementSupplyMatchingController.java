package com.toadzip.backend.ingest.mapping.controller;

import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ComplexOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.HousingTypeOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
import com.toadzip.backend.ingest.mapping.service.AnnouncementSupplyCandidateService;
import com.toadzip.backend.ingest.mapping.service.AnnouncementSupplyRowQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/ingest/announcement-supply-matches")
public class AnnouncementSupplyMatchingController {
    private final AnnouncementSupplyRowQueryService rowQuery;
    private final AnnouncementSupplyCandidateService candidates;

    @GetMapping("/{identifier}")
    public List<Row> rows(@PathVariable String identifier) {
        return rowQuery.rows(identifier);
    }

    @GetMapping("/complexes")
    public List<ComplexOption> complexes(@RequestParam String query,
            @RequestParam(defaultValue = "") String pnu) {
        return candidates.complexes(query, pnu);
    }

    @GetMapping("/complexes/{complexId}/housing-types")
    public List<HousingTypeOption> types(@PathVariable long complexId) {
        return candidates.housingTypes(complexId);
    }
}
