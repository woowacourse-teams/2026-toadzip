package com.toadzip.backend.ingest.gh.refinement.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.gh.refinement.domain.GhLotAddress;
import java.util.List;
import java.util.Optional;

/**
 * GH 공고 단지 미리 고르기(AUTO)의 후보. 휴지통이 아닌 GH 제품 단지 중 PNU의 지번이 지구 소재지와 같고 도로명 주소에 소재지의 시군구가
 * 들어 있으며 공급유형이 같은 단지가 정확히 하나일 때만 고른다. 정제 한 번에 한 번 만든다.
 */
final class GhComplexIndex {

    private static final String GH_PROVIDER = "GH";

    private final List<Candidate> candidates;

    private GhComplexIndex(List<Candidate> candidates) {
        this.candidates = candidates;
    }

    static GhComplexIndex of(List<HousingComplex> complexes) {
        return new GhComplexIndex(complexes.stream()
                .filter(complex -> !complex.isAdminDeleted() && GH_PROVIDER.equals(complex.getProvider())
                        && complex.getAddress() != null)
                .flatMap(complex -> rentalType(complex.getSupplyType()).stream()
                        .map(type -> new Candidate(complex.getId(), complex.getAddress().getPnu(),
                                complex.getAddress().getRoadAddress(), type)))
                .toList());
    }

    Optional<Long> soleCandidate(GhLotAddress location, RentalType supplyType) {
        List<Long> matched = candidates.stream()
                .filter(candidate -> candidate.supplyType() == supplyType && location.matchesPnu(candidate.pnu())
                        && candidate.roadAddress() != null
                        && candidate.roadAddress().contains(location.cityCountyDistrict()))
                .map(Candidate::complexId).toList();
        if (matched.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(matched.getFirst());
    }

    static Optional<RentalType> rentalType(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(RentalType.fromStoredValue(stored));
        }
        catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private record Candidate(long complexId, String pnu, String roadAddress, RentalType supplyType) {
    }
}
