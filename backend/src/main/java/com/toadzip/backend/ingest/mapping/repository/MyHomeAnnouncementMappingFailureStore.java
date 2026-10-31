package com.toadzip.backend.ingest.mapping.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.failure.repository.IngestFailureStore;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyHomeAnnouncementMappingFailureStore extends IngestFailureStore<MyHomeAnnouncementMappingFailure> {

    private final MyHomeAnnouncementMappingFailureRepository repository;

    public MyHomeAnnouncementMappingFailureStore(MyHomeAnnouncementMappingFailureRepository repository, Clock clock) {
        super(repository, clock);
        this.repository = repository;
    }

    @Transactional
    public void reconcileForAnnouncement(
            String identifier, List<MyHomeAnnouncementMappingFailure> failures, UUID executionId
    ) {
        if (identifier == null || identifier.isBlank() || failures.stream()
                .anyMatch(failure -> !identifier.equals(failure.getSourceAnnouncementIdentifier()))) {
            throw new IllegalArgumentException("재검사한 공고에 속한 실패만 갱신할 수 있습니다.");
        }
        reconcile(repository.findAllBySourceAnnouncementIdentifier(identifier), failures, executionId);
    }

    /** 단건 정제가 거절되어 정제 트랜잭션은 되돌려도, 전체 정제와 같은 실패 기록은 별도 트랜잭션으로 남긴다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileRejectedAnnouncement(
            String identifier, List<MyHomeAnnouncementMappingFailure> failures, UUID executionId
    ) {
        reconcileForAnnouncement(identifier, failures, executionId);
    }

    @Transactional
    public void reconcileAfterRun(List<MyHomeAnnouncementMappingFailure> failures, UUID executionId) {
        reconcileWithHistory(repository.findAllByStatus(PENDING), failures, executionId);
    }

    @Transactional
    public void resolveByAdmin(String identifier, String actor) {
        resolvePendingByAdmin(repository.findAllBySourceAnnouncementIdentifier(identifier), actor);
    }
}
