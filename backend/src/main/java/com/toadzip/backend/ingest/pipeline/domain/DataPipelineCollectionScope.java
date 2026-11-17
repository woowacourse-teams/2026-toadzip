package com.toadzip.backend.ingest.pipeline.domain;

/**
 * 자동 실행이 고른 LH 공고 수집 범위. 관리자 실행과 범위를 고르지 않은 이전 실행은 범위가 없고 전체 수집으로 본다.
 * 증분은 마감 전 공고와 막 마감된 공고만, 전체는 지금처럼 마감 뒤 한 달 안의 공고까지 다시 받는다.
 */
public enum DataPipelineCollectionScope {
    FULL,
    INCREMENTAL
}
