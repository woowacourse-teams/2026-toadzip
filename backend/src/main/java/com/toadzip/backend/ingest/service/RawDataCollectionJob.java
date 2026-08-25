package com.toadzip.backend.ingest.service;

public enum RawDataCollectionJob {

    MYHOME_COMPLEX(RawDataCategory.COMPLEX),
    LH_LEASE_CATALOG(RawDataCategory.COMPLEX),
    MYHOME_NOTICE(RawDataCategory.NOTICE),
    LH_NOTICE(RawDataCategory.NOTICE);

    private final RawDataCategory category;

    RawDataCollectionJob(RawDataCategory category) {
        this.category = category;
    }

    public RawDataCategory category() {
        return category;
    }
}
