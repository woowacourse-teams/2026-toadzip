package com.toadzip.backend.announcement.exception;

/** 이 공고의 PDF 추출 작업이 아니거나 없는 작업이다. */
public class PdfExtractionJobNotFoundException extends RuntimeException {

    public PdfExtractionJobNotFoundException() {
        super("PDF 추출 작업을 찾을 수 없습니다.");
    }
}
