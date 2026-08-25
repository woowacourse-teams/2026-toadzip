package com.toadzip.backend.ingest.service;

final class ExternalDataCallCounter {

    private int count;

    void increment() {
        count++;
    }

    int count() {
        return count;
    }
}
