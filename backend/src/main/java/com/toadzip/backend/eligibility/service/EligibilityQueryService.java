package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.eligibility.domain.EligibilityEvaluator;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.EligibilityResult;
import com.toadzip.backend.eligibility.domain.IncomeStandard;
import com.toadzip.backend.eligibility.domain.InputFact;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.Detail;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.Summary;
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

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@PreAuthorize("hasRole('USER')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class EligibilityQueryService {
    private static final int BATCH_SIZE = 32;
    private final EligibilityAnnouncementRepository announcements;
    private final EligibilityProfileRepository profiles;
    private final EligibilityReferenceRepository references;
    private final CatalogRegionMembership regions;
    private final RuleSetSnapshotCache snapshots;
    private final Clock clock;
    private final EligibilityEvaluator evaluator = new EligibilityEvaluator();

    public EligibilityQueryService(
            EligibilityAnnouncementRepository announcements,
            EligibilityProfileRepository profiles,
            EligibilityReferenceRepository references,
            CatalogRegionMembership regions,
            RuleSetSnapshotCache snapshots,
            Clock clock) {
        this.announcements = announcements;
        this.profiles = profiles;
        this.references = references;
        this.regions = regions;
        this.snapshots = snapshots;
        this.clock = clock;
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

    public Summary summary(long userId) {
        Instant now = clock.instant();
        EligibilityProfile profile = profiles.find(userId);
        Map<Long, IncomeStandard> standards = references.incomeStandards();
        Map<InputFact, Integer> inputCounts = new EnumMap<>(InputFact.class);
        int qualified = 0;
        int additionalInput = 0;
        int evaluated = 0;
        long cursor = 0;
        List<Current> batch;
        do {
            batch = announcements.activeAfter(cursor, now, BATCH_SIZE);
            for (Current current : batch) {
                EligibilityResult result = evaluate(current, profile, standards);
                evaluated++;
                cursor = current.id();
                if (result.summary().countsAsQualified()) {
                    qualified++;
                }
                if (result.needsAdditionalInput()) {
                    additionalInput++;
                    countSingleInputs(result, inputCounts);
                }
            }
        } while (batch.size() == BATCH_SIZE);
        return new Summary(
                now,
                profile.version(),
                qualified,
                additionalInput,
                evaluated,
                Map.copyOf(inputCounts),
                "ACTIVE_PUBLIC_LATEST_WITH_STRUCTURED_RULES_V1",
                EligibilityEvaluator.VERSION);
    }

    private EligibilityResult evaluate(
            Current current, EligibilityProfile profile, Map<Long, IncomeStandard> standards) {
        return evaluator.evaluate(
                snapshots.read(current.ruleSetId(), current.ruleSetVersion(), current.snapshot()),
                profile,
                standards,
                regions);
    }

    private void countSingleInputs(EligibilityResult result, Map<InputFact, Integer> counts) {
        result.options().stream()
                .filter(result::inputResolvable)
                .map(EligibilityResult.OptionResult::missingFactCodes)
                .filter(facts -> facts.size() == 1)
                .flatMap(java.util.Collection::stream)
                .distinct()
                .forEach(fact -> counts.merge(fact, 1, Integer::sum));
    }
}
