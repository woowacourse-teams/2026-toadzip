package com.toadzip.backend.ingest.lh;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.IngestReport;

@RestController
@RequestMapping("/admin/ingest/lh-notices")
public class LhNoticeIngestController {

	private final LhNoticeIngestService ingestService;

	public LhNoticeIngestController(LhNoticeIngestService ingestService) {
		this.ingestService = ingestService;
	}

	@PostMapping
	public IngestReport ingest(@RequestParam(defaultValue = "false") boolean refresh) {
		return ingestService.ingest(refresh);
	}
}
