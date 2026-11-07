package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.eligibility.domain.BasicComparisonEvaluator;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.BasicComparisonResult;
import com.toadzip.backend.eligibility.domain.IncomeStandard;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.BasicComparisonDetail;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.BasicComparisonSummary;
import com.toadzip.backend.eligibility.repository.EligibilityAnnouncementRepository;
import com.toadzip.backend.eligibility.repository.EligibilityAnnouncementRepository.Current;
import com.toadzip.backend.eligibility.repository.EligibilityJsonCodec;
import com.toadzip.backend.eligibility.repository.EligibilityProfileRepository;
import com.toadzip.backend.eligibility.repository.EligibilityReferenceRepository;
import com.toadzip.backend.eligibility.repository.EligibilityRegionMembership;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@PreAuthorize("hasRole('USER')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BasicComparisonQueryService {
    private static final int BATCH_SIZE = 32;
    private final EligibilityAnnouncementRepository announcements;
    private final EligibilityProfileRepository profiles;
    private final EligibilityReferenceRepository references;
    private final EligibilityRegionMembership regions;
    private final EligibilityJsonCodec codec;
    private final Clock clock;
    private final BasicComparisonEvaluator evaluator = new BasicComparisonEvaluator();

    public BasicComparisonQueryService(
            EligibilityAnnouncementRepository announcements,
            EligibilityProfileRepository profiles,
            EligibilityReferenceRepository references,
            EligibilityRegionMembership regions,
            EligibilityJsonCodec codec,
            Clock clock) {
        this.announcements = announcements;
        this.profiles = profiles;
        this.references = references;
        this.regions = regions;
        this.codec = codec;
        this.clock = clock;
    }

    public BasicComparisonDetail detail(long userId, long announcementId) {
        Current current = announcements.latest(announcementId);
        var stored = profiles.find(userId);
        if (!"AVAILABLE".equals(current.availability())) {
            return new BasicComparisonDetail(
                    announcementId,
                    current.id(),
                    current.availability(),
                    null,
                    null,
                    stored.version(),
                    null);
        }
        BasicComparisonResult result = evaluate(current, stored.profile(), references.incomeStandards());
        String availability = current.availability();
        if (result.scope() == BasicComparisonResult.Scope.OUT_OF_SCOPE) {
            availability = "OUT_OF_SCOPE";
        }
        return new BasicComparisonDetail(
                announcementId,
                current.id(),
                availability,
                current.ruleSetId(),
                current.ruleSetVersion(),
                stored.version(),
                result);
    }

    public BasicComparisonSummary summary(long userId) {
        Instant now = clock.instant();
        var stored = profiles.find(userId);
        Map<Long, IncomeStandard> standards = references.incomeStandards();
        SummaryCounts counts = new SummaryCounts();
        long cursor = 0;
        List<Current> batch;
        do {
            batch = announcements.activeAfter(cursor, now, BATCH_SIZE);
            for (Current current : batch) {
                cursor = current.id();
                if ("NOT_STRUCTURED".equals(current.availability())) {
                    counts.notStructured++;
                    continue;
                }
                if ("AVAILABLE".equals(current.availability())) {
                    counts.include(evaluate(current, stored.profile(), standards));
                }
            }
        } while (batch.size() == BATCH_SIZE);
        return new BasicComparisonSummary(
                now,
                stored.version(),
                counts.generalMatched,
                counts.generalInputNeeded,
                counts.requiredQualification,
                counts.compared,
                counts.notStructured,
                "ACTIVE_PUBLIC_LATEST_BASIC_COMPARISON_V2",
                BasicComparisonEvaluator.VERSION,
                "일반 경로의 기본 비교이며 최종 신청 가능 건수가 아닙니다. 필수 자격 안내 건수는 다른 건수와 겹칠 수 있습니다. "
                        + "미구조화 건수는 신청 주체도 미확인일 수 있습니다. 접수 상태는 사용자 순위를 확정하지 않습니다.");
    }

    private BasicComparisonResult evaluate(
            Current current, EligibilityProfile profile, Map<Long, IncomeStandard> standards) {
        return evaluator.evaluate(
                codec.content(current.snapshot(), current.contentSchemaVersion()),
                profile,
                standards,
                regions);
    }

    private static final class SummaryCounts {
        private int generalMatched;
        private int generalInputNeeded;
        private int requiredQualification;
        private int compared;
        private int notStructured;

        private void include(BasicComparisonResult result) {
            if (result.scope() != BasicComparisonResult.Scope.INDIVIDUAL) {
                return;
            }
            compared++;
            if (result.generalBasicMatched()) {
                generalMatched++;
            }
            if (result.generalInputNeeded()) {
                generalInputNeeded++;
            }
            if (result.requiresSeparateQualification()) {
                requiredQualification++;
            }
        }
    }
}
