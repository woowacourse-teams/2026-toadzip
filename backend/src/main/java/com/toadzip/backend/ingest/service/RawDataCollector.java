package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;

public interface RawDataCollector<C> {

    RawDataCollectionJob job();

    ExternalApiCollectionReport collect(C command);
}
