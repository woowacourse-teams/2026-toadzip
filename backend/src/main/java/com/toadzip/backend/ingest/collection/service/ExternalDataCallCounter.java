package com.toadzip.backend.ingest.collection.service;

public final class ExternalDataCallCounter {

    private int count;

    void increment() {
        count++;
    }

    void decrement() {
        count--;
    }

    public int count() {
        return count;
    }
}
