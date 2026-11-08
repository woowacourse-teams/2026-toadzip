package com.toadzip.backend.ingest.refinement.service;

import com.toadzip.backend.ingest.refinement.service.BoardRevisionLinker.LinkResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 한 번의 실행에서 정정공고를 이은 수와, 그 시점에 원공고를 찾지 못해 마무리에서 다시 시도할 정정공고를 모은다. */
public final class BoardRevisionTally {

    private final Set<String> unlinked = new LinkedHashSet<>();
    private int linked;

    public void record(String sourceKey, LinkResult result) {
        if (result == LinkResult.LINKED) {
            linked++;
            unlinked.remove(sourceKey);
        }
        else if (result == LinkResult.UNLINKED) {
            unlinked.add(sourceKey);
        }
        else {
            // 이미 이어졌거나 정정공고가 아닌 공고는 다시 시도할 대상이 아니다.
            unlinked.remove(sourceKey);
        }
    }

    /** 정제가 커밋된 원천의 정정 연결 결과를 합친다. 정정 연결을 시도하지 않은 원천은 건너뛴다. */
    public void commit(String sourceKey, BoardRefinementAttempt attempt) {
        if (attempt.link() != null) {
            record(sourceKey, attempt.link());
        }
    }

    public List<String> unlinkedKeys() {
        return List.copyOf(unlinked);
    }

    public int linked() {
        return linked;
    }

    public int unlinked() {
        return unlinked.size();
    }
}
