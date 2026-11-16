package com.toadzip.backend.ingest.refinement.domain;

/** 기관 공고 정제가 끝내지 못한 일. 정제할 원천을 읽지 못했는지, 읽은 원천 하나를 공고로 만들지 못했는지 나눈다. */
public enum AnnouncementRefinementFailureReason {
    SOURCE_LOOKUP_FAILED,
    SOURCE_REFINEMENT_FAILED
}
