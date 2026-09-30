package com.toadzip.backend.ingest.collection.service;

final class ExternalDataCallCounter {

    private int count;

    void increment() {
        count++;
    }

    void decrement() {
        count--;
    }

    int count() {
        return count;
    }
}
