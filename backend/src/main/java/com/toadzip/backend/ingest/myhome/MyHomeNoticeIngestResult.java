package com.toadzip.backend.ingest.myhome;

import com.toadzip.backend.ingest.IngestReport;

public record MyHomeNoticeIngestResult(IngestReport staging, IngestReport projection) {
}
