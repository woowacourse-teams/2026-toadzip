package com.toadzip.backend.ingest.quality.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementCollectedAtReader;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.CollectionCoverage;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import com.toadzip.backend.ingest.quality.repository.LhAnnouncementQualityStore;
import java.time.Clock;
import java.time.Instant;
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
    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementCollectedAtReader collectedAtReader;
    private final LhAnnouncementCollectionLinkRepository linkRepository;
    private final Clock clock;

    public LhAnnouncementQualityService(LhAnnouncementQualityStore store,
            LhAnnouncementCollectionCandidateResolver candidateResolver,
            LhAnnouncementCollectedAtReader collectedAtReader,
            LhAnnouncementCollectionLinkRepository linkRepository, Clock clock) {
        this.store = store;
        this.candidateResolver = candidateResolver;
        this.collectedAtReader = collectedAtReader;
        this.linkRepository = linkRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LhAnnouncementQualityResponse snapshot() {
        Instant now = clock.instant();
        List<Candidate> targets = candidateResolver.resolveCatalogTargets();
        Set<String> requestHashes = targets.stream()
                .map(candidate -> candidate.request().requestHash()).collect(Collectors.toSet());
        Set<String> linkedKeys = new HashSet<>();
        linkedKeys.addAll(linkedKeys(targets, ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY));
        linkedKeys.addAll(linkedKeys(targets, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL));
        Set<String> unlinkedCatalogSourceKeys = targets.stream()
                .map(Candidate::sourceAnnouncementKey)
                .filter(key -> !linkedKeys.contains(key))
                .map(key -> key.substring(LhAnnouncementCollectionCandidateResolver.CATALOG_SOURCE_PREFIX.length()))
                .collect(Collectors.toSet());
        return store.snapshot(now,
                collectionCoverage(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, requestHashes),
                collectionCoverage(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, requestHashes),
                unlinkedCatalogSourceKeys);
    }

    private CollectionCoverage collectionCoverage(ExternalDataSource source, Set<String> requestHashes) {
        if (requestHashes.isEmpty()) {
            return new CollectionCoverage(0, 0, null);
        }
        var collected = collectedAtReader.find(source, requestHashes);
        Instant latest = collected.values().stream().max(Instant::compareTo).orElse(null);
        return new CollectionCoverage(requestHashes.size(), collected.size(), latest);
    }

    /** 공고목록 수집 키로 저장된 연결 중 현재 조회 조건과 일치하는 것만 연결 성공으로 본다. */
    private Set<String> linkedKeys(List<Candidate> targets, ExternalDataSource source) {
        Map<String, Candidate> candidates = new HashMap<>();
        targets.forEach(candidate -> candidates.put(candidate.sourceAnnouncementKey(), candidate));
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return linkRepository.findAllBySourceAndSourceAnnouncementKeyIn(source, candidates.keySet()).stream()
                .filter(link -> {
                    Candidate candidate = candidates.get(link.getSourceAnnouncementKey());
                    return link.matches(candidate.requestDescription(), candidate.panId());
                })
                .map(link -> link.getSourceAnnouncementKey()).collect(Collectors.toSet());
    }
}
