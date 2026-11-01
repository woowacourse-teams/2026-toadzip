package com.toadzip.backend.ingest.exception.exception;

public class RetiredIngestOperationException extends RuntimeException {

    private static final String COMPLEX_REFINEMENT_MESSAGE =
            "단지 정제는 지원을 종료했습니다. 단지·주택형은 공고와 PDF 보정으로만 바뀝니다.";

    private RetiredIngestOperationException(String message) {
        super(message);
    }

    public static RetiredIngestOperationException complexRefinement() {
        return new RetiredIngestOperationException(COMPLEX_REFINEMENT_MESSAGE);
    }
}
