package com.toadzip.backend.ingest.pipeline.dto;

/** 수집을 시작하기 전에 화면이 알아야 할 서버 준비 상태. 수집 키 값은 담지 않고 설정 여부만 담는다. */
public record DataPipelineReadinessResponse(boolean serviceKeyConfigured) {
}
