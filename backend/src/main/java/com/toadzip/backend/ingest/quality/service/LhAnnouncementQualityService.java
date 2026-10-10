package com.toadzip.backend.ingest.quality.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectedAtReader;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionPolicy;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.CollectionCoverage;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import com.toadzip.backend.ingest.quality.repository.LhAnnouncementQualityStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LhAnnouncementQualityService {

    private final LhAnnouncementQualityStore store;
    private final MyHomeAnnouncementSourceReader sourceRepository;
    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementCollectionPolicy collectionPolicy;
    private final LhAnnouncementCollectedAtReader collectedAtReader;
    private final LhAnnouncementCollectionLinkRepository linkRepository;
    private final Clock clock;

    public LhAnnouncementQualityService(LhAnnouncementQualityStore store,
            MyHomeAnnouncementSourceReader sourceRepository,
            LhAnnouncementCollectionCandidateResolver candidateResolver,
            LhAnnouncementCollectionPolicy collectionPolicy,
            LhAnnouncementCollectedAtReader collectedAtReader,
            LhAnnouncementCollectionLinkRepository linkRepository, Clock clock) {
        this.store = store;
        this.sourceRepository = sourceRepository;
        this.candidateResolver = candidateResolver;
        this.collectionPolicy = collectionPolicy;
        this.collectedAtReader = collectedAtReader;
        this.linkRepository = linkRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LhAnnouncementQualityResponse snapshot() {
        Instant now = clock.instant();
        List<MyHomeAnnouncementSource> currentSources = currentSources();
        Set<String> currentSourceKeys = currentSources.stream()
                .map(MyHomeAnnouncementSource::getSourceKey).collect(Collectors.toSet());
        List<CurrentRequest> requests = currentRequests(currentSources);
        Set<String> requestHashes = collectionRequests(requests);
        Set<String> linkedPanIds = new HashSet<>();
        linkedPanIds.addAll(linkedPanIds(requests, ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY));
        linkedPanIds.addAll(linkedPanIds(requests, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL));
        return store.snapshot(now,
                collectionCoverage(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, requestHashes),
                collectionCoverage(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, requestHashes), linkedPanIds, currentSourceKeys);
    }

    private List<MyHomeAnnouncementSource> currentSources() {
        return sourceRepository.findAllByOrderByIdAsc().stream()
                .filter(source -> source.getPblancId() != null && !source.getPblancId().isBlank())
                .collect(Collectors.groupingBy(MyHomeAnnouncementSource::getPblancId))
                .values().stream()
                .flatMap(group -> MyHomeAnnouncementCurrentSources.select(group).stream()).toList();
    }

    private List<CurrentRequest> currentRequests(List<MyHomeAnnouncementSource> currentSources) {
        List<MyHomeAnnouncementSource> sources = currentSources.stream()
                .filter(MyHomeAnnouncementSource::isActive).toList();
        var resolutions = candidateResolver.resolveAll(sources);
        Map<String, Set<String>> descriptions = new HashMap<>();
        List<CurrentRequest> requests = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            if (resolutions.get(index) instanceof Candidate candidate) {
                descriptions.computeIfAbsent(candidate.sourceAnnouncementKey(), ignored -> new HashSet<>())
                        .add(candidate.requestDescription());
                requests.add(new CurrentRequest(candidate,
                        collectionPolicy.isCollectionTarget(sources.get(index))));
            }
        }
        return requests.stream()
                .filter(request -> descriptions.get(request.candidate().sourceAnnouncementKey()).size() == 1)
                .toList();
    }

    private Set<String> collectionRequests(List<CurrentRequest> requests) {
        return requests.stream()
                .filter(CurrentRequest::collectionTarget)
                .map(request -> LhAnnouncementQuery.requestHashOf(request.candidate().requestDescription()))
                .collect(Collectors.toSet());
    }

    private CollectionCoverage collectionCoverage(ExternalDataSource source, Set<String> requestHashes) {
        if (requestHashes.isEmpty()) {
            return new CollectionCoverage(0, 0, null);
        }
        var collected = collectedAtReader.find(source, requestHashes);
        Instant latest = collected.values().stream().max(Instant::compareTo).orElse(null);
        return new CollectionCoverage(requestHashes.size(), collected.size(), latest);
    }

    private Set<String> linkedPanIds(List<CurrentRequest> requests, ExternalDataSource source) {
        Map<String, Candidate> candidates = new HashMap<>();
        requests.forEach(request -> candidates.put(request.candidate().sourceAnnouncementKey(), request.candidate()));
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return linkRepository.findAllBySourceAndSourceAnnouncementKeyIn(source, candidates.keySet()).stream()
                .filter(link -> {
                    Candidate candidate = candidates.get(link.getSourceAnnouncementKey());
                    return link.matches(candidate.requestDescription(), candidate.panId());
                })
                .map(link -> link.getPanId()).collect(Collectors.toSet());
    }

    private record CurrentRequest(Candidate candidate, boolean collectionTarget) {
    }

}
