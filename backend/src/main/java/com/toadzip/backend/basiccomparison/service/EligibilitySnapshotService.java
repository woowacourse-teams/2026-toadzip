package com.toadzip.backend.basiccomparison.service;

import com.toadzip.backend.basiccomparison.domain.ComparisonContent;
import com.toadzip.backend.basiccomparison.domain.EligibilityBasis;
import com.toadzip.backend.basiccomparison.domain.EligibilityDecision;
import com.toadzip.backend.basiccomparison.domain.EligibilityEvaluator;
import com.toadzip.backend.basiccomparison.domain.IncomeStandard;
import com.toadzip.backend.basiccomparison.domain.StoredEligibilityResult;
import com.toadzip.backend.basiccomparison.repository.ComparisonAnnouncementRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonJsonCodec;
import com.toadzip.backend.basiccomparison.repository.ComparisonProfileRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonReferenceRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonRegionMembership;
import com.toadzip.backend.basiccomparison.repository.EligibilityWorkRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class EligibilitySnapshotService {
    private final ComparisonAnnouncementRepository announcements;
    private final ComparisonProfileRepository profiles;
    private final ComparisonReferenceRepository references;
    private final ComparisonRegionMembership regions;
    private final ComparisonJsonCodec codec;
    private final EligibilityWorkRepository work;
    private final Clock clock;

    public EligibilitySnapshotService(ComparisonAnnouncementRepository announcements,
            ComparisonProfileRepository profiles,
            ComparisonReferenceRepository references, ComparisonRegionMembership regions, ComparisonJsonCodec codec,
            EligibilityWorkRepository work, Clock clock) {
        this.announcements = announcements;
        this.profiles = profiles;
        this.references = references;
        this.regions = regions;
        this.codec = codec;
        this.work = work;
        this.clock = clock;
    }

    public EligibilityBasis basis(long userId, long announcementId) {
        long root = announcements.rootId(announcementId);
        var current = announcements.latestMetadata(root);
        long profileVersion = profiles.version(userId);
        var state = work.notice(root, false).orElseThrow();
        String phase = announcements.applicationStatus(current.id(),
                LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        return new EligibilityBasis(userId, root, current.id(), profileVersion, state.generation(),
                current.ruleSetId(), current.availability(), phase, EligibilityEvaluator.VERSION);
    }

    public Snapshot snapshot(long userId, long announcementId) {
        var basis = basis(userId, announcementId);
        ComparisonContent content = content(basis);
        return new Snapshot(basis, content);
    }

    public NoticeInput noticeInput(long rootId) {
        var basis = basis(0, rootId);
        return new NoticeInput(basis, content(basis), references.incomeStandards());
    }

    public StoredEligibilityResult evaluate(long userId, long rootId) {
        return evaluate(userId, noticeInput(rootId));
    }

    public StoredEligibilityResult evaluate(long userId, NoticeInput notice) {
        var profile = profiles.find(userId);
        var source = notice.basis();
        var basis = new EligibilityBasis(userId, source.rootAnnouncementId(), source.announcementId(),
                profile.version(), source.noticeGeneration(), source.ruleSetId(), source.availability(),
                source.applicationStatus(), source.evaluatorVersion());
        EligibilityDecision decision = null;
        if (basis.active()) {
            decision = new EligibilityEvaluator().evaluate(
                    notice.content(), profile.profile(), notice.standards(), regions);
        }
        return new StoredEligibilityResult(basis, decision, clock.instant());
    }

    private ComparisonContent content(EligibilityBasis basis) {
        if (!"AVAILABLE".equals(basis.availability())) {
            return null;
        }
        var current = announcements.latest(basis.rootAnnouncementId());
        return codec.content(current.snapshot(), current.contentSchemaVersion());
    }

    public record NoticeInput(EligibilityBasis basis, ComparisonContent content,
            Map<Long, IncomeStandard> standards) {}

    public record Snapshot(EligibilityBasis basis, ComparisonContent content) {}
}
