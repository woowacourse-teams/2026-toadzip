package com.toadzip.backend.ingest.collection.domain;

public enum ExternalDataSource {
    MYHOME_COMPLEX("myhome-complex"),
    MYHOME_ANNOUNCEMENT("myhome-announcement"),
    LH_LEASE_CATALOG("lh-lease-catalog"),
    LH_ANNOUNCEMENT_DETAIL("lh-announcement-detail"),
    LH_ANNOUNCEMENT_SUPPLY("lh-announcement-supply");

    private final String operation;

    ExternalDataSource(String operation) {
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }
}
