package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore.BatchProgress;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LhAnnouncementCollectionProgressManager {

    private final LhAnnouncementCollectionProgressStore progressStore;
    private final ExternalDataFailureRecorder failureRecorder;

    public BatchProgress findBatch(ExternalDataSource targetSource, List<Candidate> candidates) {
        return progressStore.findBatch(
                targetSource,
                candidates.stream().map(Candidate::requestDescription).toList(),
                candidates.stream().map(Candidate::panId).toList(),
                candidates.stream().map(Candidate::sourceAnnouncementKey).toList()
        );
    }

    public void complete(ExternalDataSource targetSource, Candidate candidate) {
        resolveFailures(targetSource, candidate);
        progressStore.complete(
                targetSource,
                candidate.sourceAnnouncementKey(),
                candidate.requestDescription(),
                candidate.panId()
        );
    }

    private void resolveFailures(ExternalDataSource targetSource, Candidate candidate) {
        failureRecorder.resolve(targetSource, candidate.requestDescription());
        if (!candidate.requestDescription().equals(candidate.sourceDescription())) {
            failureRecorder.resolve(targetSource, candidate.sourceDescription());
        }
    }
}
