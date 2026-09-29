package com.toadzip.backend.ingest.quality.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionCheckpointRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementRefreshPolicy;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Freshness;
import com.toadzip.backend.ingest.quality.repository.LhAnnouncementQualityStore;
import java.time.Clock;
import java.time.Duration;
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
    private final MyHomeAnnouncementSourceRepository sourceRepository;
    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementRefreshPolicy refreshPolicy;
    private final LhAnnouncementCollectionCheckpointRepository checkpointRepository;
    private final LhAnnouncementCollectionLinkRepository linkRepository;
    private final Clock clock;

    public LhAnnouncementQualityService(LhAnnouncementQualityStore store,
            MyHomeAnnouncementSourceRepository sourceRepository,
            LhAnnouncementCollectionCandidateResolver candidateResolver,
            LhAnnouncementRefreshPolicy refreshPolicy,
            LhAnnouncementCollectionCheckpointRepository checkpointRepository,
            LhAnnouncementCollectionLinkRepository linkRepository, Clock clock) {
        this.store = store;
        this.sourceRepository = sourceRepository;
        this.candidateResolver = candidateResolver;
        this.refreshPolicy = refreshPolicy;
        this.checkpointRepository = checkpointRepository;
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
        Map<String, RefreshRequirement> requirements = refreshRequirements(requests, now);
        Set<String> linkedPanIds = new HashSet<>();
        linkedPanIds.addAll(linkedPanIds(requests, ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY));
        linkedPanIds.addAll(linkedPanIds(requests, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL));
        return store.snapshot(now,
                freshness(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, requirements),
                freshness(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, requirements), linkedPanIds, currentSourceKeys);
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
                        refreshPolicy.scheduledRefreshTtl(sources.get(index), candidate).orElse(null)));
            }
        }
        return requests.stream()
                .filter(request -> descriptions.get(request.candidate().sourceAnnouncementKey()).size() == 1)
                .toList();
    }

    private Map<String, RefreshRequirement> refreshRequirements(List<CurrentRequest> requests, Instant now) {
        Map<String, RefreshRequirement> requirements = new HashMap<>();
        for (CurrentRequest request : requests) {
            if (request.refreshTtl() != null) {
                Candidate candidate = request.candidate();
                String hash = LhAnnouncementCollectionCheckpoint.requestHashOf(candidate.requestDescription());
                requirements.merge(hash, new RefreshRequirement(now.minus(request.refreshTtl()),
                        candidate.catalogChangedAt()), RefreshRequirement::strictest);
            }
        }
        return requirements;
    }

    private Freshness freshness(ExternalDataSource source, Map<String, RefreshRequirement> requirements) {
        if (requirements.isEmpty()) {
            return new Freshness(0, 0, null);
        }
        var checkpoints = checkpointRepository.findAllBySourceAndRequestHashIn(source, requirements.keySet());
        long fresh = checkpoints.stream()
                .filter(checkpoint -> requirements.get(checkpoint.getRequestHash()).accepts(checkpoint.getCompletedAt()))
                .count();
        Instant latest = checkpoints.stream().map(LhAnnouncementCollectionCheckpoint::getCompletedAt)
                .max(Instant::compareTo).orElse(null);
        return new Freshness(requirements.size(), fresh, latest);
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

    private record CurrentRequest(Candidate candidate, Duration refreshTtl) {
    }

    private record RefreshRequirement(Instant freshAfter, Instant catalogChangedAt) {

        private boolean accepts(Instant completedAt) {
            return completedAt.isAfter(freshAfter)
                    && (catalogChangedAt == null || !completedAt.isBefore(catalogChangedAt));
        }

        private RefreshRequirement strictest(RefreshRequirement other) {
            return new RefreshRequirement(latest(freshAfter, other.freshAfter),
                    latest(catalogChangedAt, other.catalogChangedAt));
        }

        private static Instant latest(Instant first, Instant second) {
            if (first == null) {
                return second;
            }
            if (second == null || first.isAfter(second)) {
                return first;
            }
            return second;
        }
    }
}
