package com.toadzip.backend.streetview.service;

import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.repository.ComplexDetailQueryRepository;
import com.toadzip.backend.streetview.domain.StreetViewPolicy;
import com.toadzip.backend.streetview.dto.StreetViewResponse.StreetViewInitialization;
import com.toadzip.backend.streetview.dto.StreetViewResponse.StreetViewPosition;
import com.toadzip.backend.streetview.dto.StreetViewResponse;
import com.toadzip.backend.streetview.exception.StreetViewPolicyUnavailableException;
import com.toadzip.backend.streetview.repository.StreetViewPolicyRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StreetViewQueryService {
    private final ComplexDetailQueryRepository complexes;
    private final StreetViewPolicyRepository policies;

    public StreetViewQueryService(ComplexDetailQueryRepository complexes, StreetViewPolicyRepository policies) {
        this.complexes = complexes;
        this.policies = policies;
    }

    public StreetViewResponse get(long complexId) {
        var complex = complexes.findComplex(complexId).orElseThrow(HousingComplexNotFoundException::new);
        var policy = policies.findById(StreetViewPolicy.POLICY_ID)
                .orElseThrow(StreetViewPolicyUnavailableException::new);
        if (!policy.isEnabled()) {
            return new StreetViewResponse(complex.complexId(), "NAVER", false, "POLICY_DISABLED",
                    policy.getVersion(), null);
        }
        if (!validCoordinates(complex.latitude(), complex.longitude())) {
            return new StreetViewResponse(complex.complexId(), "NAVER", false, "INVALID_COORDINATES",
                    policy.getVersion(), null);
        }
        var position = new StreetViewPosition(complex.latitude(), complex.longitude());
        return new StreetViewResponse(complex.complexId(), "NAVER", true, null, policy.getVersion(),
                new StreetViewInitialization(position, position, 0, 90));
    }

    private boolean validCoordinates(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            return false;
        }
        if (latitude.signum() == 0 && longitude.signum() == 0) {
            return false;
        }
        return latitude.abs().compareTo(BigDecimal.valueOf(90)) <= 0
                && longitude.abs().compareTo(BigDecimal.valueOf(180)) <= 0;
    }
}
