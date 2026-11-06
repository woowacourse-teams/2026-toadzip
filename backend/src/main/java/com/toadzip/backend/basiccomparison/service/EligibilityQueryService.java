package com.toadzip.backend.basiccomparison.service;

import com.toadzip.backend.basiccomparison.domain.EligibilityEvaluator;
import com.toadzip.backend.basiccomparison.dto.EligibilityDetail;
import com.toadzip.backend.basiccomparison.exception.InvalidComparisonException;
import com.toadzip.backend.basiccomparison.repository.ComparisonAnnouncementRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonJsonCodec;
import com.toadzip.backend.basiccomparison.repository.ComparisonProfileRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonReferenceRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonRegionMembership;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('USER')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class EligibilityQueryService {
    private final ComparisonAnnouncementRepository announcements;
    private final ComparisonProfileRepository profiles;
    private final ComparisonReferenceRepository references;
    private final ComparisonRegionMembership regions;
    private final ComparisonJsonCodec codec;
    private final EligibilityEvaluator evaluator = new EligibilityEvaluator();

    public EligibilityQueryService(ComparisonAnnouncementRepository announcements,
            ComparisonProfileRepository profiles, ComparisonReferenceRepository references,
            ComparisonRegionMembership regions, ComparisonJsonCodec codec) {
        this.announcements = announcements;
        this.profiles = profiles;
        this.references = references;
        this.regions = regions;
        this.codec = codec;
    }

    public EligibilityDetail detail(long userId, long announcementId, String selectedRegion) {
        if (selectedRegion != null && !regions.isKnown(selectedRegion)) {
            throw new InvalidComparisonException("지역 카탈로그에 등록된 탐색 지역을 선택해 주세요.");
        }
        var current = announcements.latest(announcementId);
        var profile = profiles.find(userId);
        if (!"AVAILABLE".equals(current.availability())) {
            return new EligibilityDetail(announcementId, current.id(), current.availability(), current.ruleSetId(),
                    profile.version(), null, null, selectedRegion, List.of());
        }
        var content = codec.content(current.snapshot(), current.contentSchemaVersion());
        var result = evaluator.evaluate(content, profile.profile(), references.incomeStandards(), regions);
        List<String> selected = result.eligibleOptionIds();
        if (selectedRegion != null) {
            selected = result.eligibleOptionIdsIn(selectedRegion, regions);
        }
        return new EligibilityDetail(announcementId, current.id(), current.availability(), current.ruleSetId(),
                profile.version(), result, content, selectedRegion, selected);
    }
}
