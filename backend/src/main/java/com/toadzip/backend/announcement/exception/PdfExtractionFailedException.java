package com.toadzip.backend.announcement.exception;

/** AI 모델 호출이 실패했거나 쓸 수 없는 응답을 돌려줬다. */
public class PdfExtractionFailedException extends RuntimeException {

    public PdfExtractionFailedException(String message) {
        super(message);
    }

    public PdfExtractionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
