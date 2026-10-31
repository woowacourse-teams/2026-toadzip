package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Resolution;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link LhAnnouncementCollectionCandidateResolver}가 원천마다 해석한 후보 중 이번 실행에서 수집할 후보를 고른다.
 * 한 마이홈 공고의 요청이 서로 다르면 충돌로, 앞 배치에서 이미 다룬 공고는 중복으로 빼고,
 * 정기 수집이면 수집 정책 밖의 공고도 뺀다.
 */
@Component
@RequiredArgsConstructor
public class LhAnnouncementCandidateSelector {

    private final LhAnnouncementCollectionPolicy collectionPolicy;

    /** 고른 공고와 충돌로 보고할 공고를 {@code handledAnnouncementKeys}에 더한다. 호출자는 배치 사이에 같은 집합을 넘긴다. */
    CandidateSelection select(
            List<MyHomeAnnouncementSource> sources,
            List<Resolution> resolutions,
            Set<String> handledAnnouncementKeys,
            boolean forceRefresh
    ) {
        Set<String> conflictingKeys = conflictingSourceKeys(resolutions);
        Set<String> sourceKeysWithoutEligibleCandidates = resolutions.stream()
                .map(Resolution::sourceAnnouncementKey)
                .collect(Collectors.toSet());
        List<Skipped> skippedSources = new ArrayList<>();
        List<Candidate> conflicts = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        int policyExcludedCount = 0;
        int duplicateCount = 0;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            Resolution resolution = resolutions.get(sourceIndex);
            if (resolution instanceof Skipped skipped) {
                skippedSources.add(skipped);
                continue;
            }
            Candidate candidate = (Candidate) resolution;
            if (!forceRefresh && !collectionPolicy.isCollectionTarget(sources.get(sourceIndex))) {
                policyExcludedCount++;
                continue;
            }
            sourceKeysWithoutEligibleCandidates.remove(candidate.sourceAnnouncementKey());
            if (conflictingKeys.contains(candidate.sourceAnnouncementKey())) {
                addConflict(candidate, handledAnnouncementKeys, conflicts);
                continue;
            }
            if (!handledAnnouncementKeys.add(candidate.sourceAnnouncementKey())) {
                duplicateCount++;
                continue;
            }
            candidates.add(candidate);
        }
        return new CandidateSelection(
                candidates, skippedSources, conflicts, sourceKeysWithoutEligibleCandidates,
                policyExcludedCount, duplicateCount
        );
    }


    private Set<String> conflictingSourceKeys(List<Resolution> resolutions) {
        Map<String, Set<String>> requestsBySource = new HashMap<>();
        for (Resolution resolution : resolutions) {
            if (!(resolution instanceof Candidate candidate)) {
                continue;
            }
            requestsBySource.computeIfAbsent(candidate.sourceAnnouncementKey(), ignored -> new HashSet<>())
                    .add(candidate.requestDescription());
        }
        return requestsBySource.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }


    private void addConflict(Candidate candidate, Set<String> visited, List<Candidate> conflicts) {
        if (visited.add(candidate.sourceAnnouncementKey())) {
            conflicts.add(candidate);
        }
    }


    record CandidateSelection(
            List<Candidate> candidates,
            List<Skipped> skipped,
            List<Candidate> conflicts,
            Set<String> sourceKeysWithoutEligibleCandidates,
            int policyExcludedCount,
            int duplicateCount
    ) {
    }
}
