package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.eligibility.domain.EligibilityEvaluator;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.EligibilityResult;
import com.toadzip.backend.eligibility.domain.IncomeStandard;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.Detail;
import com.toadzip.backend.eligibility.repository.CatalogRegionMembership;
import com.toadzip.backend.eligibility.repository.EligibilityAnnouncementRepository;
import com.toadzip.backend.eligibility.repository.EligibilityAnnouncementRepository.Current;
import com.toadzip.backend.eligibility.repository.EligibilityProfileRepository;
import com.toadzip.backend.eligibility.repository.EligibilityReferenceRepository;
import com.toadzip.backend.eligibility.repository.RuleSetSnapshotCache;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@PreAuthorize("hasRole('USER')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class EligibilityQueryService {
    private final EligibilityAnnouncementRepository announcements;
    private final EligibilityProfileRepository profiles;
    private final EligibilityReferenceRepository references;
    private final CatalogRegionMembership regions;
    private final RuleSetSnapshotCache snapshots;
    private final EligibilityEvaluator evaluator = new EligibilityEvaluator();

    public EligibilityQueryService(
            EligibilityAnnouncementRepository announcements,
            EligibilityProfileRepository profiles,
            EligibilityReferenceRepository references,
            CatalogRegionMembership regions,
            RuleSetSnapshotCache snapshots) {
        this.announcements = announcements;
        this.profiles = profiles;
        this.references = references;
        this.regions = regions;
        this.snapshots = snapshots;
    }

    public Detail detail(long userId, long announcementId) {
        Current current = announcements.latest(announcementId);
        EligibilityProfile profile = profiles.find(userId);
        if (!"AVAILABLE".equals(current.availability())) {
            return new Detail(
                    announcementId,
                    current.id(),
                    current.availability(),
                    null,
                    null,
                    profile.version(),
                    null,
                    null);
        }
        EligibilityResult result = evaluate(current, profile, references.incomeStandards());
        return new Detail(
                announcementId,
                current.id(),
                current.availability(),
                current.ruleSetId(),
                current.ruleSetVersion(),
                profile.version(),
                snapshots
                        .read(current.ruleSetId(), current.ruleSetVersion(), current.snapshot())
                        .metadata(),
                result);
    }

    private EligibilityResult evaluate(
            Current current, EligibilityProfile profile, Map<Long, IncomeStandard> standards) {
        return evaluator.evaluate(
                snapshots.read(current.ruleSetId(), current.ruleSetVersion(), current.snapshot()),
                profile,
                standards,
                regions);
    }
}
