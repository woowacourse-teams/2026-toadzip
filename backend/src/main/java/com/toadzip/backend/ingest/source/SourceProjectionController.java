package com.toadzip.backend.ingest.source;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.NoticeSupplyCatalogLinker;

@RestController
@RequestMapping("/admin/ingest")
public class SourceProjectionController {

	private final NoticeSupplyCatalogLinker catalogLinker;

	private final SourceProjectionService sourceProjectionService;

	public SourceProjectionController(NoticeSupplyCatalogLinker catalogLinker,
			SourceProjectionService sourceProjectionService) {
		this.catalogLinker = catalogLinker;
		this.sourceProjectionService = sourceProjectionService;
	}

	@PostMapping("/links")
	public IngestReport linkCatalog() {
		return catalogLinker.linkAll();
	}

	@PostMapping("/rebuild-from-sources")
	public SourceProjectionReport rebuildFromSources() {
		return sourceProjectionService.project();
	}
}
