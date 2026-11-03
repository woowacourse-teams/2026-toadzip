package com.toadzip.backend.announcement.exception;

/** 이 공고에 아직 끝나지 않은 PDF 추출 작업이 있다. 화면은 진행 중 작업을 이어서 기다린다. */
public class PdfExtractionInProgressException extends RuntimeException {

    public PdfExtractionInProgressException() {
        super("이미 진행 중인 PDF 추출 작업이 있습니다.");
    }
}
