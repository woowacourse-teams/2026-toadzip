package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;

public interface DataPipelineProgressListener {

    void started(DataPipelineStep step);

    void completed(DataPipelineStep step);

    void skipped(DataPipelineStep step, String reason, String serverResponse);
}
