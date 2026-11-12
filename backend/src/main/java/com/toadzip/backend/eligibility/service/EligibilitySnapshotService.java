package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.EligibilityBasis;
import com.toadzip.backend.eligibility.domain.EligibilityDecision;
import com.toadzip.backend.eligibility.domain.EligibilityEvaluator;
import com.toadzip.backend.eligibility.domain.IncomeStandard;
import com.toadzip.backend.eligibility.domain.StoredEligibilityResult;
import com.toadzip.backend.eligibility.repository.EligibilityAnnouncementRepository;
import com.toadzip.backend.eligibility.repository.EligibilityJsonCodec;
import com.toadzip.backend.eligibility.repository.EligibilityProfileRepository;
import com.toadzip.backend.eligibility.repository.EligibilityReferenceRepository;
import com.toadzip.backend.eligibility.repository.EligibilityRegionMembership;
import com.toadzip.backend.eligibility.repository.EligibilityWorkRepository;
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
    private final EligibilityAnnouncementRepository announcements;
    private final EligibilityProfileRepository profiles;
    private final EligibilityReferenceRepository references;
    private final EligibilityRegionMembership regions;
    private final EligibilityJsonCodec codec;
    private final EligibilityWorkRepository work;
    private final Clock clock;

    public EligibilitySnapshotService(EligibilityAnnouncementRepository announcements,
            EligibilityProfileRepository profiles,
            EligibilityReferenceRepository references, EligibilityRegionMembership regions, EligibilityJsonCodec codec,
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
        EligibilityContent content = content(basis);
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

    private EligibilityContent content(EligibilityBasis basis) {
        if (!"AVAILABLE".equals(basis.availability())) {
            return null;
        }
        var current = announcements.latest(basis.rootAnnouncementId());
        return codec.content(current.snapshot(), current.contentSchemaVersion());
    }

    public record NoticeInput(EligibilityBasis basis, EligibilityContent content,
            Map<Long, IncomeStandard> standards) {}

    public record Snapshot(EligibilityBasis basis, EligibilityContent content) {}
}
