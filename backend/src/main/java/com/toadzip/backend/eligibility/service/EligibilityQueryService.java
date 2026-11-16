package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.eligibility.domain.EligibilityBasis;
import com.toadzip.backend.eligibility.domain.EligibilityDecision;
import com.toadzip.backend.eligibility.domain.StoredEligibilityResult;
import com.toadzip.backend.eligibility.dto.EligibilityDetail;
import com.toadzip.backend.eligibility.dto.EligibilityProcessingResponses.Discoveries;
import com.toadzip.backend.eligibility.dto.EligibilityProcessingResponses.Discovery;
import com.toadzip.backend.eligibility.dto.EligibilityProcessingResponses.Progress;
import com.toadzip.backend.eligibility.exception.EligibilityConflictException;
import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;
import com.toadzip.backend.eligibility.repository.EligibilityProfileRepository;
import com.toadzip.backend.eligibility.repository.EligibilityRegionMembership;
import com.toadzip.backend.eligibility.repository.EligibilityResultRepository;
import com.toadzip.backend.eligibility.repository.EligibilityWorkRepository;
import com.toadzip.backend.eligibility.service.EligibilitySnapshotService.Snapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('USER')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class EligibilityQueryService {
    private static final int MAX_BATCH_SIZE = 100;

    private final EligibilitySnapshotService snapshots;
    private final EligibilityProfileRepository profiles;
    private final EligibilityRegionMembership regions;
    private final EligibilityWorkRepository work;
    private final EligibilityResultRepository results;
    private final Clock clock;

    public EligibilityQueryService(EligibilitySnapshotService snapshots, EligibilityProfileRepository profiles,
            EligibilityRegionMembership regions, EligibilityWorkRepository work,
            EligibilityResultRepository results, Clock clock) {
        this.snapshots = snapshots;
        this.profiles = profiles;
        this.regions = regions;
        this.work = work;
        this.results = results;
        this.clock = clock;
    }

    public EligibilityDetail detail(long userId, long announcementId, String selectedRegion) {
        requireKnownRegion(selectedRegion);
        var snapshot = snapshots.snapshot(userId, announcementId);
        var stored = results.find(userId, snapshot.basis().rootAnnouncementId());
        return read(snapshot, stored, announcementId, selectedRegion);
    }

    /**
     * 목록이 공고마다 단건 조회를 따로 부르지 않게, 같은 스냅샷에서 요청 순서대로 단건 조회와 같은 결과를 돌려준다.
     * DB도 공고 수와 관계없이 같은 횟수의 쿼리로 읽는다.
     */
    public List<EligibilityDetail> details(long userId, List<Long> announcementIds, String selectedRegion) {
        if (announcementIds.isEmpty() || announcementIds.size() > MAX_BATCH_SIZE) {
            throw new InvalidEligibilityException("공고 ID를 1~" + MAX_BATCH_SIZE + "개 보내 주세요.");
        }
        requireKnownRegion(selectedRegion);
        var found = snapshots.snapshots(userId, announcementIds);
        var stored = results.findAll(userId,
                found.stream().map(snapshot -> snapshot.basis().rootAnnouncementId()).distinct().toList());
        List<EligibilityDetail> details = new ArrayList<>();
        for (int index = 0; index < found.size(); index++) {
            var snapshot = found.get(index);
            details.add(read(snapshot, Optional.ofNullable(stored.get(snapshot.basis().rootAnnouncementId())),
                    announcementIds.get(index), selectedRegion));
        }
        return List.copyOf(details);
    }

    private void requireKnownRegion(String selectedRegion) {
        if (selectedRegion != null && !regions.isKnown(selectedRegion)) {
            throw new InvalidEligibilityException("지역 카탈로그에 등록된 탐색 지역을 선택해 주세요.");
        }
    }

    private EligibilityDetail read(Snapshot snapshot, Optional<StoredEligibilityResult> stored, long announcementId,
            String selectedRegion) {
        var basis = snapshot.basis();
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
            throw new InvalidEligibilityException("조회 커서와 1~100 범위의 개수를 확인해 주세요.");
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
            throw new EligibilityConflictException();
        }
        var current = snapshots.basis(userId, rootId);
        var stored = results.find(userId, rootId);
        if (!current.active() || stored.isEmpty() || !current.equals(stored.get().basis())
                || !results.acknowledge(userId, rootId, eventId, clock.instant())) {
            throw new EligibilityConflictException();
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
