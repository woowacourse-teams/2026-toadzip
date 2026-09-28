package com.toadzip.backend.ingest.pipeline.service;

public class DataPipelineStoppedException extends RuntimeException {

    public DataPipelineStoppedException() {
        super("관리자가 실행 중지를 요청했습니다.");
    }
}
