package com.toadzip.backend.ingest.refinement.service;

import com.toadzip.backend.ingest.refinement.service.BoardRevisionLinker.LinkResult;

/**
 * 원천 하나를 정제하는 동안 모은 집계. 정제가 커밋된 뒤에만 실행 집계에 합쳐야 하므로, 롤백된 원천의 정정 연결이나 공급행
 * 재확인 필요가 실행 집계에 남지 않는다.
 */
public final class BoardRefinementAttempt {

    private LinkResult link;
    private boolean supplyRecheckRequired;

    public void recordLink(LinkResult link) {
        this.link = link;
    }

    public void requireSupplyRecheck() {
        supplyRecheckRequired = true;
    }

    /** 이 원천에서 정정 연결을 시도했으면 그 결과, 아니면 null. */
    public LinkResult link() {
        return link;
    }

    public boolean supplyRecheckRequired() {
        return supplyRecheckRequired;
    }
}
