package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
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
    public void complete(ExternalDataSource targetSource, Candidate candidate) {
        progressStore.complete(
                targetSource,
                candidate.sourceAnnouncementKey(),
                candidate.requestDescription(),
                candidate.panId()
        );
        resolveFailures(targetSource, candidate);
    }

    @Transactional
    public void link(ExternalDataSource targetSource, Candidate candidate) {
        progressStore.link(
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
