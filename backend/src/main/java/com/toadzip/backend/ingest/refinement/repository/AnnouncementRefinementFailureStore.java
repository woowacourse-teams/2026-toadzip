package com.toadzip.backend.ingest.refinement.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;
import static com.toadzip.backend.ingest.refinement.domain.AnnouncementRefinementFailureReason.SOURCE_LOOKUP_FAILED;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.failure.repository.IngestFailureStore;
import com.toadzip.backend.ingest.refinement.domain.AnnouncementRefinementFailure;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기관 공고 정제 실패를 실행마다 맞춘다. 한 테이블에 LH·SH·GH가 함께 있고 실행마다 다루는 원천이 일부뿐이므로, 그 기관에서 이번
 * 실행이 다시 정제한 원천의 실패만 해결로 바꾼다. 다른 기관이나 이번에 다루지 않은 원천의 실패는 그대로 둔다.
 *
 * <p>묶음 읽기 실패의 키는 페이지·묶음의 첫 원천으로 만들어 실행마다 달라지므로 키로 다시 만나지 못한다. 그래서 그 기관의 실행이
 * 끝까지 돌면 지난 묶음 읽기 실패는 키와 상관없이 맞추고, 이번에 다시 난 것만 남긴다. 도중에 멈춘 실행은 읽지 못한 묶음이 있으니
 * 다룬 키만 맞춘다.
 */
@Repository
public class AnnouncementRefinementFailureStore extends IngestFailureStore<AnnouncementRefinementFailure> {

    private final AnnouncementRefinementFailureRepository repository;

    public AnnouncementRefinementFailureStore(AnnouncementRefinementFailureRepository repository, Clock clock) {
        super(repository, clock);
        this.repository = repository;
    }

    /** 끝까지 돈 실행의 실패를 맞춘다. */
    @Transactional
    public void reconcile(AgencyCode provider, Collection<String> attemptedKeys,
            List<AnnouncementRefinementFailure> observed, UUID executionId) {
        reconcile(provider, attemptedKeys, observed, executionId, true);
    }

    /** 도중에 예외로 멈춘 실행이 그때까지 본 실패를 맞춘다. */
    @Transactional
    public void reconcileStopped(AgencyCode provider, Collection<String> attemptedKeys,
            List<AnnouncementRefinementFailure> observed, UUID executionId) {
        reconcile(provider, attemptedKeys, observed, executionId, false);
    }

    private void reconcile(AgencyCode provider, Collection<String> attemptedKeys,
            List<AnnouncementRefinementFailure> observed, UUID executionId, boolean finished) {
        List<AnnouncementRefinementFailure> stored = repository.findAllByProviderAndStatus(provider, PENDING).stream()
                .filter(failure -> attemptedKeys.contains(failure.getSourceKey())
                        || (finished && failure.getReason() == SOURCE_LOOKUP_FAILED))
                .toList();
        reconcileWithHistory(stored, observed, executionId);
    }
}
