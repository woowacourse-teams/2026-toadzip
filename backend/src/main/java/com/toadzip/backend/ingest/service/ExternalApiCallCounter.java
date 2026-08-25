package com.toadzip.backend.ingest.service;

final class ExternalApiCallCounter {

    private int count;

    void increment() {
        count++;
    }

    int count() {
        return count;
    }
}
