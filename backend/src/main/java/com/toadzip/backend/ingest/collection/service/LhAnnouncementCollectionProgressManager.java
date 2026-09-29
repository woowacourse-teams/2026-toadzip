package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore.BatchProgress;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LhAnnouncementCollectionProgressManager {

    private final LhAnnouncementCollectionProgressStore progressStore;
    private final ExternalDataFailureRecorder failureRecorder;
    private final Clock clock;
    public BatchProgress findBatch(
            ExternalDataSource targetSource,
            List<Candidate> candidates,
            Duration candidateRefreshTtl
    ) {
        if (candidateRefreshTtl.isZero() || candidateRefreshTtl.isNegative()) {
            throw new IllegalArgumentException("LH 공고 재수집 만료 시간은 0보다 커야 합니다.");
        }
        Map<String, Instant> changedAtByRequest = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.catalogChangedAt() != null) {
                changedAtByRequest.merge(candidate.requestDescription(), candidate.catalogChangedAt(),
                        BinaryOperator.maxBy(Comparator.naturalOrder()));
            }
        }
        return progressStore.findBatch(
                targetSource,
                candidates.stream().map(Candidate::requestDescription).toList(),
                candidates.stream().map(Candidate::sourceAnnouncementKey).toList(),
                clock.instant().minus(candidateRefreshTtl),
                changedAtByRequest
        );
    }

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
        failureRecorder.resolve(targetSource, candidate.request().previousRequestDescription());
        String previousPageRequest = candidate.request().previousPageRequestDescription();
        failureRecorder.resolve(targetSource, previousPageRequest);
        failureRecorder.resolveStartingWith(targetSource, previousPageRequest + "&PG_SZ=");
        if (!candidate.requestDescription().equals(candidate.sourceDescription())) {
            failureRecorder.resolve(targetSource, candidate.sourceDescription());
        }
    }
}
