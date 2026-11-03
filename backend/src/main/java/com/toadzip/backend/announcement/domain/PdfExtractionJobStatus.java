package com.toadzip.backend.announcement.domain;

/** ai_jobs.status. Python 워커가 쓰고 Spring이 읽는 값이라 마이그레이션의 CHECK 제약과 같아야 한다. */
public enum PdfExtractionJobStatus {
    PENDING,
    RUNNING,
    RETRY,
    SUCCEEDED,
    FAILED
}
