package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;

public interface DataPipelineProgressListener {

    void started(DataPipelineStep step);

    void completed(DataPipelineStep step, String report);

    void completedWithWarnings(DataPipelineStep step, String report);

    void skipped(DataPipelineStep step, String reason, String serverResponse);

    void partiallyFailed(DataPipelineStep step, String report);
}
