package com.toadzip.backend.ingest.refinement.domain;

/** SH·GH 게시판 원천 하나를 정제한 결과. */
public enum BoardRefinementOutcome {
    CREATED, UPDATED, UNCHANGED, REVIEW_REQUIRED, TRASHED
}
