package com.toadzip.backend.announcement.exception;

/** 이 서버에 PDF 추출용 AI 모델이 설정되지 않았다. */
public class PdfExtractionUnavailableException extends RuntimeException {

    public PdfExtractionUnavailableException() {
        super("PDF 추출용 AI 모델이 설정되지 않았습니다.");
    }
}
