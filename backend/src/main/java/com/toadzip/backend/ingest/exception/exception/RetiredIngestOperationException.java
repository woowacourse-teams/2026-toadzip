package com.toadzip.backend.ingest.exception.exception;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;

public class RetiredIngestOperationException extends RuntimeException {

    private static final String COMPLEX_REFINEMENT_MESSAGE =
            "단지 정제는 지원을 종료했습니다. 단지·주택형은 공고와 PDF 보정으로만 바뀝니다.";

    private static final String MYHOME_ANNOUNCEMENT_MESSAGE =
            "마이홈 공고 정제·LH 보강과 그 보정은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다.";

    private RetiredIngestOperationException(String message) {
        super(message);
    }

    public static RetiredIngestOperationException complexRefinement() {
        return new RetiredIngestOperationException(COMPLEX_REFINEMENT_MESSAGE);
    }

    public static RetiredIngestOperationException myHomeAnnouncementRefinement() {
        return new RetiredIngestOperationException(MYHOME_ANNOUNCEMENT_MESSAGE);
    }

    /** 지원을 종료한 파이프라인 유형의 안내. */
    public static RetiredIngestOperationException of(DataPipelineType type) {
        if (type == DataPipelineType.ANNOUNCEMENT_REGISTRATION) {
            return myHomeAnnouncementRefinement();
        }
        return complexRefinement();
    }
}
