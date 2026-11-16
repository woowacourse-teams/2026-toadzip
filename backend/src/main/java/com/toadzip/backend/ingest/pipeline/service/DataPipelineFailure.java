package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineFailureCause;
import java.util.ArrayList;
import java.util.List;

/**
 * 예상하지 못한 예외로 끝난 실행의 원인과 요약. 외부 기관 오류가 원인에 있으면 다시 실행하면 되는 실패로 보고, 비밀값을 가린
 * 그 메시지를 남긴다. 그 밖은 내부 오류로 보고, SQL·값이 섞일 수 있는 메시지 대신 예외 종류만 남긴다. 전체 내용은 서버 로그에
 * 실행 ID와 함께 있다.
 */
public record DataPipelineFailure(DataPipelineFailureCause cause, String message, String detail) {

    static final String INTERNAL_MESSAGE = "현재 단계를 처리하는 중 서버 오류가 발생했습니다.";
    static final String EXTERNAL_MESSAGE = "외부 기관 응답 문제로 현재 단계를 끝내지 못했습니다.";
    private static final int MAX_DETAIL_LENGTH = 500;

    public static DataPipelineFailure of(RuntimeException exception) {
        List<String> types = new ArrayList<>();
        for (Throwable cause = exception; cause != null && types.size() < 10; cause = cause.getCause()) {
            if (cause instanceof ExternalDataCallFailureException || cause instanceof ExternalDataRequestException) {
                return new DataPipelineFailure(DataPipelineFailureCause.TRANSIENT, EXTERNAL_MESSAGE,
                        limit(ExternalDataFailureRecorder.redactSecrets(singleLine(cause.getMessage()))));
            }
            types.add(cause.getClass().getSimpleName());
        }
        return new DataPipelineFailure(DataPipelineFailureCause.INTERNAL, INTERNAL_MESSAGE,
                limit(String.join(" ← ", types)));
    }

    private static String singleLine(String message) {
        if (message == null || message.isBlank()) {
            return "외부 기관이 이유를 알리지 않았습니다.";
        }
        return message.replaceAll("[\\r\\n]+", " ");
    }

    private static String limit(String value) {
        return value.substring(0, Math.min(value.length(), MAX_DETAIL_LENGTH));
    }
}
