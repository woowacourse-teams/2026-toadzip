package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.DataPipelineStep;

public interface DataPipelineProgressListener {

    void started(DataPipelineStep step);

    void completed(DataPipelineStep step);
}
