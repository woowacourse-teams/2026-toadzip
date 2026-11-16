package com.toadzip.backend.ingest.refinement.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

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
 */
@Repository
public class AnnouncementRefinementFailureStore extends IngestFailureStore<AnnouncementRefinementFailure> {

    private final AnnouncementRefinementFailureRepository repository;

    public AnnouncementRefinementFailureStore(AnnouncementRefinementFailureRepository repository, Clock clock) {
        super(repository, clock);
        this.repository = repository;
    }

    @Transactional
    public void reconcile(AgencyCode provider, Collection<String> attemptedKeys,
            List<AnnouncementRefinementFailure> observed, UUID executionId) {
        List<AnnouncementRefinementFailure> stored = repository.findAllByProviderAndStatus(provider, PENDING).stream()
                .filter(failure -> attemptedKeys.contains(failure.getSourceKey()))
                .toList();
        reconcileWithHistory(stored, observed, executionId);
    }
}
