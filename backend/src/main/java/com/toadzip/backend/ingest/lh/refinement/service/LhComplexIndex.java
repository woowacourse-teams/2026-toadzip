package com.toadzip.backend.ingest.lh.refinement.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.location.domain.RoadNumberAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 공고 단지 자동 연결(AUTO)의 후보 색인. 휴지통이 아닌 LH 제품 단지를 (주소 키, 공급유형)으로 묶는다. 정제 한 번에 한 번 만든다.
 * 후보가 정확히 하나일 때만 연결한다. 같은 주소·같은 유형의 단지가 둘 이상이면 고르지 않는다.
 */
final class LhComplexIndex {

    private static final String LH_PROVIDER = "LH";

    private final Map<Key, List<Long>> complexIds = new HashMap<>();

    static LhComplexIndex of(List<HousingComplex> complexes) {
        LhComplexIndex index = new LhComplexIndex();
        for (HousingComplex complex : complexes) {
            if (complex.isAdminDeleted() || !LH_PROVIDER.equals(complex.getProvider()) || complex.getAddress() == null) {
                continue;
            }
            Optional<RentalType> type = rentalType(complex.getSupplyType());
            Optional<RoadNumberAddress> address = RoadNumberAddress.parse(complex.getAddress().getRoadAddress());
            if (type.isPresent() && address.isPresent()) {
                index.complexIds.computeIfAbsent(new Key(address.get(), type.get()), ignored -> new ArrayList<>())
                        .add(complex.getId());
            }
        }
        return index;
    }

    /** 공고 상세 주소와 공고 공급유형이 같은 단지가 하나뿐이면 그 단지 ID. */
    Optional<Long> soleCandidate(String roadAddress, RentalType supplyType) {
        return RoadNumberAddress.parse(roadAddress)
                .map(address -> complexIds.getOrDefault(new Key(address, supplyType), List.of()))
                .filter(candidates -> candidates.size() == 1)
                .map(List::getFirst);
    }

    private static Optional<RentalType> rentalType(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(RentalType.fromStoredValue(stored));
        }
        catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private record Key(RoadNumberAddress address, RentalType supplyType) {
    }
}
