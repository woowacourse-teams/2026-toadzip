package com.toadzip.backend.basiccomparison.service;

import com.toadzip.backend.basiccomparison.domain.EligibilityWork.Kind;
import com.toadzip.backend.basiccomparison.domain.EligibilityWork;
import com.toadzip.backend.basiccomparison.repository.ComparisonAnnouncementRepository;
import com.toadzip.backend.basiccomparison.repository.EligibilityResultRepository;
import com.toadzip.backend.basiccomparison.repository.EligibilityWorkRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EligibilityRecheckWorker {
    private static final Logger log = LoggerFactory.getLogger(EligibilityRecheckWorker.class);
    private final EligibilityRecheckTransactions transactions;
    private final EligibilitySnapshotService snapshots;
    private final EligibilityWorkRepository work;
    private final EligibilityResultRepository results;
    private final ComparisonAnnouncementRepository announcements;
    private final Clock clock;
    private final int batchSize;
    private long reconcileCursor;

    public EligibilityRecheckWorker(EligibilityRecheckTransactions transactions, EligibilitySnapshotService snapshots,
            EligibilityWorkRepository work, EligibilityResultRepository results,
            ComparisonAnnouncementRepository announcements, Clock clock,
            @Value("${comparison.recheck.batch-size:32}") int batchSize) {
        this.transactions = transactions;
        this.snapshots = snapshots;
        this.work = work;
        this.results = results;
        this.announcements = announcements;
        this.clock = clock;
        this.batchSize = Math.clamp(batchSize, 1, 1000);
    }

    public boolean runOnce(Kind kind) {
        var claimed = transactions.claim(kind);
        if (claimed.isEmpty()) {
            return false;
        }
        EligibilityWork job = claimed.get();
        try {
            process(job);
        } catch (RuntimeException exception) {
            transactions.retry(job, "EVALUATION_FAILED");
            log.warn("Eligibility recheck deferred: kind={}, id={}, errorType={}",
                    kind, job.id(), exception.getClass().getSimpleName());
        }
        return true;
    }

    private void process(EligibilityWork job) {
        List<Long> targets = targets(job);
        EligibilitySnapshotService.NoticeInput notice = null;
        if (job.kind() == Kind.NOTICE && !job.retired()) {
            notice = snapshots.noticeInput(job.id());
        }
        long cursor = job.cursor();
        for (long target : targets) {
            boolean committed = processTarget(job, target, notice);
            if (!committed) {
                transactions.retry(job, "BASIS_CHANGED");
                return;
            }
            cursor = target;
            if (!transactions.checkpoint(job, cursor)) {
                transactions.retry(job, "LEASE_EXPIRED");
                return;
            }
        }
        transactions.progress(job, cursor, targets.size() < batchSize);
    }

    private boolean processTarget(EligibilityWork job, long target, EligibilitySnapshotService.NoticeInput notice) {
        if (job.retired()) {
            return transactions.purgeRetired(job, target);
        }
        long userId = job.id();
        long rootId = target;
        if (job.kind() == Kind.NOTICE) {
            userId = target;
            rootId = job.id();
        }
        if (notice != null) {
            return transactions.commit(job, snapshots.evaluate(userId, notice));
        }
        return transactions.commit(job, snapshots.evaluate(userId, rootId));
    }

    private List<Long> targets(EligibilityWork job) {
        if (job.kind() == Kind.PROFILE) {
            return announcements.eligibleRootsAfter(job.cursor(),
                    LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))), batchSize);
        }
        if (job.retired() || !snapshots.basis(0, job.id()).active()) {
            return results.resultUsersAfter(job.id(), job.cursor(), batchSize);
        }
        return work.usersAfter(job.cursor(), batchSize);
    }

    /** A bounded, restartable scan catches calendar and evaluator changes without resetting same-basis work. */
    public void reconcileOnce() {
        List<Long> roots = work.rootsAfter(reconcileCursor, batchSize);
        for (long root : roots) {
            var basis = snapshots.basis(0, root);
            if (root == basis.rootAnnouncementId()) {
                transactions.observe(root, basis.noticeGeneration(), basis.noticeKey());
            }
            reconcileCursor = root;
        }
        if (roots.size() < batchSize) {
            reconcileCursor = 0;
        }
    }
}
