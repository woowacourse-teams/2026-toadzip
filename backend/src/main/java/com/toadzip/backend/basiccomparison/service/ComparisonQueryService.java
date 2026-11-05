package com.toadzip.backend.basiccomparison.service;

import com.toadzip.backend.basiccomparison.domain.BasicComparisonEvaluator;
import com.toadzip.backend.basiccomparison.domain.ComparisonProfile;
import com.toadzip.backend.basiccomparison.domain.ComparisonResult;
import com.toadzip.backend.basiccomparison.domain.IncomeStandard;
import com.toadzip.backend.basiccomparison.dto.ComparisonResponses.Detail;
import com.toadzip.backend.basiccomparison.dto.ComparisonResponses.Summary;
import com.toadzip.backend.basiccomparison.repository.ComparisonAnnouncementRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonAnnouncementRepository.Current;
import com.toadzip.backend.basiccomparison.repository.ComparisonJsonCodec;
import com.toadzip.backend.basiccomparison.repository.ComparisonProfileRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonReferenceRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonRegionMembership;

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
public class ComparisonQueryService {
    private static final int BATCH_SIZE = 32;
    private final ComparisonAnnouncementRepository announcements;
    private final ComparisonProfileRepository profiles;
    private final ComparisonReferenceRepository references;
    private final ComparisonRegionMembership regions;
    private final ComparisonJsonCodec codec;
    private final Clock clock;
    private final BasicComparisonEvaluator evaluator = new BasicComparisonEvaluator();

    public ComparisonQueryService(
            ComparisonAnnouncementRepository announcements,
            ComparisonProfileRepository profiles,
            ComparisonReferenceRepository references,
            ComparisonRegionMembership regions,
            ComparisonJsonCodec codec,
            Clock clock) {
        this.announcements = announcements;
        this.profiles = profiles;
        this.references = references;
        this.regions = regions;
        this.codec = codec;
        this.clock = clock;
    }

    public Detail detail(long userId, long announcementId) {
        Current current = announcements.latest(announcementId);
        var stored = profiles.find(userId);
        if (!"AVAILABLE".equals(current.availability())) {
            return new Detail(
                    announcementId,
                    current.id(),
                    current.availability(),
                    null,
                    null,
                    stored.version(),
                    null);
        }
        ComparisonResult result = evaluate(current, stored.profile(), references.incomeStandards());
        String availability = current.availability();
        if (result.scope() == ComparisonResult.Scope.OUT_OF_SCOPE) {
            availability = "OUT_OF_SCOPE";
        }
        return new Detail(
                announcementId,
                current.id(),
                availability,
                current.ruleSetId(),
                current.ruleSetVersion(),
                stored.version(),
                result);
    }

    public Summary summary(long userId) {
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
        return new Summary(
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

    private ComparisonResult evaluate(
            Current current, ComparisonProfile profile, Map<Long, IncomeStandard> standards) {
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

        private void include(ComparisonResult result) {
            if (result.scope() != ComparisonResult.Scope.INDIVIDUAL) {
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
