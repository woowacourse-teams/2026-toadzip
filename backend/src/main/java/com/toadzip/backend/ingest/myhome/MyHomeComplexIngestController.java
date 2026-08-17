package com.toadzip.backend.ingest.myhome;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/ingest/complexes")
public class MyHomeComplexIngestController {

	private final MyHomeComplexIngestService ingestService;

	public MyHomeComplexIngestController(MyHomeComplexIngestService ingestService) {
		this.ingestService = ingestService;
	}

	@PostMapping
	public MyHomeComplexIngestResult ingest(@RequestParam(defaultValue = "1000") @Min(1) @Max(1000) int pageSize,
			@RequestParam(defaultValue = "50") @Min(1) @Max(1000) int maxPages) {
		return ingestService.ingestNationwide(pageSize, maxPages);
	}

}
