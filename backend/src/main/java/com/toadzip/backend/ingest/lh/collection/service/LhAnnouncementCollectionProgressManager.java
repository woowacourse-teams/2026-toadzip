package com.toadzip.backend.ingest.lh.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LhAnnouncementCollectionProgressManager {

    private final LhAnnouncementCollectionProgressStore progressStore;
    private final ExternalDataFailureRecorder failureRecorder;

    @Transactional
    public void recordLinked(ExternalDataSource targetSource, Candidate candidate) {
        progressStore.save(
                targetSource,
                candidate.sourceAnnouncementKey(),
                candidate.requestDescription(),
                candidate.panId()
        );
        resolveFailures(targetSource, candidate);
    }

    private void resolveFailures(ExternalDataSource targetSource, Candidate candidate) {
        failureRecorder.resolve(targetSource, candidate.requestDescription());
        if (!candidate.requestDescription().equals(candidate.sourceDescription())) {
            failureRecorder.resolve(targetSource, candidate.sourceDescription());
        }
    }
}
