package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.InputFact;
import com.toadzip.backend.eligibility.domain.InputValue;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveProfile;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.Catalog;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.Profile;
import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;
import com.toadzip.backend.eligibility.repository.CatalogRegionMembership;
import com.toadzip.backend.eligibility.repository.EligibilityProfileRepository;
import com.toadzip.backend.eligibility.repository.EligibilityReferenceRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;

@Service
@PreAuthorize("hasRole('USER')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class EligibilityProfileService {
    private final EligibilityProfileRepository profiles;
    private final EligibilityReferenceRepository references;
    private final CatalogRegionMembership regions;
    private final Clock clock;

    public EligibilityProfileService(
            EligibilityProfileRepository profiles,
            EligibilityReferenceRepository references,
            CatalogRegionMembership regions,
            Clock clock) {
        this.profiles = profiles;
        this.references = references;
        this.regions = regions;
        this.clock = clock;
    }

    public Profile find(long userId) {
        return Profile.of(profiles.find(userId));
    }

    @Transactional
    public Profile save(long userId, SaveProfile request) {
        EligibilityProfile profile =
                new EligibilityProfile(null, request.expectedVersion(), null, request.facts());
        profile.validateForSave(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        validateRegions(profile.facts());
        profiles.save(userId, request.expectedVersion(), profile, clock.instant());
        return Profile.of(profiles.find(userId));
    }

    public Catalog catalog() {
        return new Catalog(
                references.facts(),
                references.gaps(),
                Arrays.stream(InputFact.values()).filter(InputFact::allowsNotApplicable).toList());
    }

    private void validateRegions(Map<InputFact, InputValue> facts) {
        facts.forEach(
                (fact, value) -> {
                    if (fact.valueType() == InputFact.ValueType.REGION
                            && value.value() instanceof String code
                            && !regions.isKnown(code)) {
                        throw new InvalidEligibilityException(fact + ": 지역 카탈로그에 등록된 코드를 입력해 주세요.");
                    }
                });
    }
}
