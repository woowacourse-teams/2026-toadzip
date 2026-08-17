package com.toadzip.backend.ingest.myhome;

import com.toadzip.backend.ingest.IngestReport;

public record MyHomeComplexIngestResult(IngestReport staging, IngestReport projection) {
}
