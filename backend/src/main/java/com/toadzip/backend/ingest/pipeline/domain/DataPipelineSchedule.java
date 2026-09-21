package com.toadzip.backend.ingest.pipeline.domain;

public enum DataPipelineSchedule {
    COMPLEX(
            DataPipelineType.COMPLEX_COLLECTION,
            DataPipelineType.COMPLEX_REFINEMENT
    ),
    ANNOUNCEMENT(
            DataPipelineType.ANNOUNCEMENT_COLLECTION,
            DataPipelineType.ANNOUNCEMENT_REFINEMENT
    );

    private final DataPipelineType collectionType;
    private final DataPipelineType refinementType;

    DataPipelineSchedule(
            DataPipelineType collectionType,
            DataPipelineType refinementType
    ) {
        this.collectionType = collectionType;
        this.refinementType = refinementType;
    }

    public DataPipelineType collectionType() {
        return collectionType;
    }

    public DataPipelineType refinementType() {
        return refinementType;
    }
}
