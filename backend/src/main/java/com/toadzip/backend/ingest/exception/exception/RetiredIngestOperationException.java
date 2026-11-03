package com.toadzip.backend.ingest.exception.exception;

public class RetiredIngestOperationException extends RuntimeException {

    private RetiredIngestOperationException(String message) {
        super(message);
    }

    public static RetiredIngestOperationException complexRefinement() {
        return of(Retirement.COMPLEX_REFINEMENT);
    }

    public static RetiredIngestOperationException myHomeAnnouncementRefinement() {
        return of(Retirement.MYHOME_ANNOUNCEMENT_REFINEMENT);
    }

    public static RetiredIngestOperationException myHomeAnnouncementCollection() {
        return of(Retirement.MYHOME_ANNOUNCEMENT_COLLECTION);
    }

    public static RetiredIngestOperationException of(Retirement retirement) {
        return new RetiredIngestOperationException(retirement.message);
    }

    /** 지원을 종료한 작업의 종류와 그 안내. */
    public enum Retirement {
        COMPLEX_REFINEMENT("단지 정제는 지원을 종료했습니다. 단지·주택형은 공고와 PDF 보정으로만 바뀝니다."),
        MYHOME_ANNOUNCEMENT_REFINEMENT(
                "마이홈 공고 정제·LH 보강과 그 보정은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다."
        ),
        MYHOME_ANNOUNCEMENT_COLLECTION(
                "마이홈 공고 수집은 지원을 종료했습니다. 공고는 LH 공고목록 기준으로만 수집합니다."
        );

        private final String message;

        Retirement(String message) {
            this.message = message;
        }
    }
}
