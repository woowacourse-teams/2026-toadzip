package com.toadzip.backend.ingest.lh;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.IngestReport;

@RestController
@RequestMapping("/admin/ingest/lease-infos")
public class LhLeaseInfoIngestController {

	private final LhLeaseInfoIngestService ingestService;

	public LhLeaseInfoIngestController(LhLeaseInfoIngestService ingestService) {
		this.ingestService = ingestService;
	}

	@PostMapping
	public IngestReport ingest(@RequestParam(defaultValue = "9999") @Min(1) @Max(10_000) int pageSize,
			@RequestParam(defaultValue = "1") @Min(1) @Max(10_000) int maxPages) {
		return ingestService.ingest(pageSize, maxPages);
	}

}
