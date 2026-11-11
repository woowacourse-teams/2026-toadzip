package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.eligibility.domain.EligibilityDecision;
import com.toadzip.backend.eligibility.domain.EligibilityWork.Kind;
import com.toadzip.backend.eligibility.domain.EligibilityWork;
import com.toadzip.backend.eligibility.domain.StoredEligibilityResult;
import com.toadzip.backend.eligibility.repository.EligibilityResultRepository;
import com.toadzip.backend.eligibility.repository.EligibilityWorkRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short write transactions only. No evaluation or entire fan-out under a row lock. */
@Service
public class EligibilityRecheckTransactions {
    private final EligibilityWorkRepository work;
    private final EligibilityResultRepository results;
    private final EligibilitySnapshotService snapshots;
    private final Clock clock;
    private final int leaseSeconds;

    public EligibilityRecheckTransactions(EligibilityWorkRepository work, EligibilityResultRepository results,
            EligibilitySnapshotService snapshots, Clock clock,
            @Value("${comparison.recheck.lease-seconds:120}") int leaseSeconds) {
        this.work = work;
        this.results = results;
        this.snapshots = snapshots;
        this.clock = clock;
        this.leaseSeconds = Math.max(1, leaseSeconds);
    }

    @Transactional
    public Optional<EligibilityWork> claim(Kind kind) {
        return work.claim(kind, clock.instant(), leaseSeconds);
    }

    @Transactional
    public boolean commit(EligibilityWork claimed, StoredEligibilityResult evaluated) {
        var basis = evaluated.basis();
        if (claimed.kind() == Kind.PROFILE && claimed.id() != basis.userId()) {
            return false;
        }
        if (!work.lockUser(basis.userId())) {
            return true;
        }
        if (claimed.kind() == Kind.PROFILE && !work.lockClaim(claimed, clock.instant())) {
            return false;
        }
        var notice = work.notice(basis.rootAnnouncementId(), true);
        if (notice.isEmpty() || notice.get().retired()) {
            return false;
        }
        if (claimed.kind() == Kind.NOTICE && !work.lockClaim(claimed, clock.instant())) {
            return false;
        }
        var current = snapshots.basis(basis.userId(), basis.rootAnnouncementId());
        if (!basis.equals(current) || !clock.instant().isBefore(claimed.leaseUntil())
                || claimed.kind() == Kind.NOTICE && claimed.id() != current.rootAnnouncementId()
                || claimed.kind() == Kind.PROFILE && claimed.revision() != current.profileVersion()) {
            return false;
        }
        work.observe(basis.rootAnnouncementId(), basis.noticeGeneration(), basis.noticeKey());
        if (notice.get().observedBasis() != null && !notice.get().observedBasis().equals(basis.noticeKey())) {
            return false;
        }
        Optional<StoredEligibilityResult> previous = results.find(basis.userId(), basis.rootAnnouncementId());
        if (previous.isPresent() && previous.get().basis().equals(basis)) {
            return true;
        }
        results.save(new StoredEligibilityResult(basis, evaluated.result(), clock.instant()));
        updateDiscovery(evaluated, previous);
        return true;
    }

    private void updateDiscovery(StoredEligibilityResult current, Optional<StoredEligibilityResult> previous) {
        List<String> eligible = eligible(current.result());
        List<String> earlier = previous.map(stored -> eligible(stored.result())).orElse(List.of());
        List<String> newlyEligible = eligible.stream().filter(option -> !earlier.contains(option)).toList();
        if (!newlyEligible.isEmpty()) {
            results.newDiscovery(current.basis(), newlyEligible, clock.instant());
            return;
        }
        var discovery = results.discovery(current.basis().userId(), current.basis().rootAnnouncementId());
        if (discovery.isPresent()) {
            results.refreshDiscovery(current.basis(), discovery.get().optionIds().stream()
                    .filter(eligible::contains).toList());
        }
    }

    private List<String> eligible(EligibilityDecision result) {
        if (result == null) {
            return List.of();
        }
        return result.eligibleOptionIds();
    }

    @Transactional
    public boolean purgeRetired(EligibilityWork claimed, long userId) {
        if (!work.lockUser(userId)) {
            return true;
        }
        if (!work.lockClaim(claimed, clock.instant())) {
            return false;
        }
        var state = work.notice(claimed.id(), false);
        if (state.isEmpty() || !state.get().retired()) {
            return false;
        }
        results.delete(userId, claimed.id());
        return true;
    }

    @Transactional
    public boolean checkpoint(EligibilityWork claimed, long cursor) {
        return work.checkpoint(claimed, cursor, clock.instant());
    }

    @Transactional
    public void progress(EligibilityWork claimed, long cursor, boolean completed) {
        work.progress(claimed, cursor, completed, clock.instant());
    }

    @Transactional
    public void retry(EligibilityWork claimed, String errorCode) {
        work.retry(claimed, clock.instant(), errorCode);
    }

    @Transactional
    public void observe(long rootId, long generation, String key) {
        work.observe(rootId, generation, key);
    }
}
