package com.toadzip.backend.basiccomparison.service;

import com.toadzip.backend.basiccomparison.domain.EligibilityBasis;
import com.toadzip.backend.basiccomparison.domain.EligibilityDecision;
import com.toadzip.backend.basiccomparison.dto.EligibilityDetail;
import com.toadzip.backend.basiccomparison.dto.EligibilityProcessingResponses.Discoveries;
import com.toadzip.backend.basiccomparison.dto.EligibilityProcessingResponses.Discovery;
import com.toadzip.backend.basiccomparison.dto.EligibilityProcessingResponses.Progress;
import com.toadzip.backend.basiccomparison.exception.ComparisonConflictException;
import com.toadzip.backend.basiccomparison.exception.InvalidComparisonException;
import com.toadzip.backend.basiccomparison.repository.ComparisonProfileRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonRegionMembership;
import com.toadzip.backend.basiccomparison.repository.EligibilityResultRepository;
import com.toadzip.backend.basiccomparison.repository.EligibilityWorkRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('USER')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class EligibilityQueryService {
    private final EligibilitySnapshotService snapshots;
    private final ComparisonProfileRepository profiles;
    private final ComparisonRegionMembership regions;
    private final EligibilityWorkRepository work;
    private final EligibilityResultRepository results;
    private final Clock clock;

    public EligibilityQueryService(EligibilitySnapshotService snapshots, ComparisonProfileRepository profiles,
            ComparisonRegionMembership regions, EligibilityWorkRepository work,
            EligibilityResultRepository results, Clock clock) {
        this.snapshots = snapshots;
        this.profiles = profiles;
        this.regions = regions;
        this.work = work;
        this.results = results;
        this.clock = clock;
    }

    public EligibilityDetail detail(long userId, long announcementId, String selectedRegion) {
        if (selectedRegion != null && !regions.isKnown(selectedRegion)) {
            throw new InvalidComparisonException("지역 카탈로그에 등록된 탐색 지역을 선택해 주세요.");
        }
        var snapshot = snapshots.snapshot(userId, announcementId);
        var basis = snapshot.basis();
        var stored = results.find(userId, basis.rootAnnouncementId());
        List<String> reasons = new ArrayList<>();
        EligibilityDecision decision = null;
        Instant evaluatedAt = null;
        Long evaluatedProfileVersion = null;
        String freshness = "RECHECKING";
        if (stored.isPresent()) {
            evaluatedAt = stored.get().evaluatedAt();
            evaluatedProfileVersion = stored.get().basis().profileVersion();
            reasons.addAll(staleReasons(basis, stored.get().basis()));
            if (reasons.isEmpty()) {
                decision = stored.get().result();
                freshness = "CURRENT";
            }
        }
        if (stored.isEmpty()) {
            reasons.add("NOT_EVALUATED_YET");
        }
        if (!basis.active()) {
            decision = null;
            freshness = "NOT_AVAILABLE";
        }
        List<String> selected = List.of();
        if (decision != null) {
            selected = decision.eligibleOptionIds();
            if (selectedRegion != null) {
                selected = decision.eligibleOptionIdsIn(selectedRegion, regions);
            }
        }
        return new EligibilityDetail(announcementId, basis.announcementId(), basis.availability(), basis.ruleSetId(),
                basis.profileVersion(), decision, snapshot.content(), selectedRegion, selected,
                basis.applicationStatus(),
                freshness, List.copyOf(reasons), evaluatedAt, evaluatedProfileVersion);
    }

    public Progress progress(long userId) {
        var profile = profiles.find(userId);
        var state = work.profileProgress(userId);
        return new Progress(profile.version(), state.requestedVersion(), state.state(), state.failedAttempts());
    }

    public Discoveries discoveries(long userId, long afterAnnouncementId, int limit) {
        if (afterAnnouncementId < 0 || limit < 1 || limit > 100) {
            throw new InvalidComparisonException("조회 커서와 1~100 범위의 개수를 확인해 주세요.");
        }
        var candidates = results.discoveriesAfter(userId, afterAnnouncementId, limit);
        List<Discovery> visible = new ArrayList<>();
        long cursor = afterAnnouncementId;
        for (var candidate : candidates) {
            cursor = candidate.announcementId();
            var current = snapshots.basis(userId, candidate.announcementId());
            var stored = results.find(userId, current.rootAnnouncementId());
            if (current.active() && current.rootAnnouncementId() == candidate.announcementId()
                    && stored.isPresent() && current.equals(stored.get().basis())
                    && candidate.profileVersion() == current.profileVersion()
                    && candidate.noticeGeneration() == current.noticeGeneration()) {
                visible.add(new Discovery(candidate.eventId(), candidate.announcementId(), current.announcementId(),
                        candidate.optionIds(), candidate.discoveredAt()));
            }
        }
        return new Discoveries(List.copyOf(visible), cursor, candidates.size() == limit);
    }

    @Transactional
    public void acknowledge(long userId, long rootId, UUID eventId) {
        work.lockUser(userId);
        var notice = work.notice(rootId, true);
        if (notice.isEmpty() || notice.get().retired()) {
            throw new ComparisonConflictException();
        }
        var current = snapshots.basis(userId, rootId);
        var stored = results.find(userId, rootId);
        if (!current.active() || stored.isEmpty() || !current.equals(stored.get().basis())
                || !results.acknowledge(userId, rootId, eventId, clock.instant())) {
            throw new ComparisonConflictException();
        }
    }

    private List<String> staleReasons(EligibilityBasis current, EligibilityBasis previous) {
        List<String> reasons = new ArrayList<>();
        if (current.profileVersion() != previous.profileVersion()) {
            reasons.add("PROFILE_CHANGED");
        }
        if (!current.noticeKey().equals(previous.noticeKey())
                || current.noticeGeneration() != previous.noticeGeneration()) {
            reasons.add("NOTICE_CHANGED");
        }
        return reasons;
    }
}
