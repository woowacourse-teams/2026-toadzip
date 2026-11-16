package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.EligibilityBasis;
import com.toadzip.backend.eligibility.domain.EligibilityDecision;
import com.toadzip.backend.eligibility.domain.EligibilityEvaluator;
import com.toadzip.backend.eligibility.domain.IncomeStandard;
import com.toadzip.backend.eligibility.domain.StoredEligibilityResult;
import com.toadzip.backend.eligibility.exception.EligibilityNotFoundException;
import com.toadzip.backend.eligibility.repository.EligibilityAnnouncementRepository;
import com.toadzip.backend.eligibility.repository.EligibilityAnnouncementRepository.Current;
import com.toadzip.backend.eligibility.repository.EligibilityAnnouncementRepository.Latest;
import com.toadzip.backend.eligibility.repository.EligibilityJsonCodec;
import com.toadzip.backend.eligibility.repository.EligibilityProfileRepository;
import com.toadzip.backend.eligibility.repository.EligibilityReferenceRepository;
import com.toadzip.backend.eligibility.repository.EligibilityRegionMembership;
import com.toadzip.backend.eligibility.repository.EligibilityWorkRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        String phase = announcements.applicationStatus(current.id(), today());
        return basis(userId, root, current, profileVersion, state.generation(), phase);
    }

    public Snapshot snapshot(long userId, long announcementId) {
        var basis = basis(userId, announcementId);
        EligibilityContent content = content(basis);
        return new Snapshot(basis, content);
    }

    /**
     * 여러 공고의 스냅샷을 요청 순서대로 만든다. 공고 수와 관계없이 같은 횟수의 쿼리로 읽으며,
     * 공고마다 {@link #snapshot}을 부른 것과 같은 결과다. 없는 공고가 하나라도 있으면 실패한다.
     */
    public List<Snapshot> snapshots(long userId, List<Long> announcementIds) {
        var latest = announcements.latestByRequest(announcementIds, today());
        long profileVersion = profiles.version(userId);
        var notices = work.notices(latest.values().stream().map(Latest::rootId).distinct().toList());
        return announcementIds.stream().map(id -> {
            var found = latest.get(id);
            if (found == null) {
                throw new EligibilityNotFoundException();
            }
            var state = Optional.ofNullable(notices.get(found.rootId())).orElseThrow();
            var basis = basis(userId, found.rootId(), found.current(), profileVersion, state.generation(),
                    found.applicationStatus());
            EligibilityContent content = null;
            if (available(basis)) {
                content = decode(found.current());
            }
            return new Snapshot(basis, content);
        }).toList();
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

    private EligibilityBasis basis(long userId, long root, Current current, long profileVersion, long generation,
            String phase) {
        return new EligibilityBasis(userId, root, current.id(), profileVersion, generation,
                current.ruleSetId(), current.availability(), phase, EligibilityEvaluator.VERSION);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    }

    private boolean available(EligibilityBasis basis) {
        return "AVAILABLE".equals(basis.availability());
    }

    private EligibilityContent content(EligibilityBasis basis) {
        if (!available(basis)) {
            return null;
        }
        return decode(announcements.latest(basis.rootAnnouncementId()));
    }

    private EligibilityContent decode(Current current) {
        return codec.content(current.snapshot(), current.contentSchemaVersion());
    }

    public record NoticeInput(EligibilityBasis basis, EligibilityContent content,
            Map<Long, IncomeStandard> standards) {}

    public record Snapshot(EligibilityBasis basis, EligibilityContent content) {}
}
