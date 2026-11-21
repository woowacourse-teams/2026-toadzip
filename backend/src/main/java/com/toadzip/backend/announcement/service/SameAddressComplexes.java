package com.toadzip.backend.announcement.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.location.domain.RoadNumberAddress;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PDF 보정에서 공고 단지와 같은 곳의 기존 단지. LH 정제의 공고 단지 자동 연결(AUTO)과 같은 기준으로, 주소의 (시도, 도로명, 건물번호)와
 * 공급유형이 같은 휴지통 밖 단지다. 주소는 공고문 단지 개요 주소와 원천 도로명주소를 함께 받고, 어느 쪽에서 찾았든 한 번만 센다.
 * 건물번호가 없는 주소(동 단위·준공 전 블록)는 같은 곳을 가리킬 수 없어 찾지 않는다. 기관(공급자)은 비교하지 않는다. 같은 단지가
 * 수집 경로에 따라 공급자 '기타'로 저장된 경우가 있어서다.
 */
@Component
@RequiredArgsConstructor
class SameAddressComplexes {
    private final HousingComplexRepository complexes;

    /** 후보가 정확히 하나일 때만 관리자 선택 없이 잇는다. 둘 이상이면 관리자가 고른다. */
    List<HousingComplex> find(Collection<String> addresses, RentalType supplyType) {
        Map<Long, HousingComplex> found = new LinkedHashMap<>();
        addresses.stream().filter(Objects::nonNull).map(RoadNumberAddress::parse).flatMap(Optional::stream).distinct()
                .forEach(key -> complexes.findAllActiveByRoad(key.road()).stream()
                        .filter(complex -> sameSupplyType(complex, supplyType) && complex.getAddress() != null
                                && RoadNumberAddress.parse(complex.getAddress().getRoadAddress()).equals(Optional.of(key)))
                        .forEach(complex -> found.putIfAbsent(complex.getId(), complex)));
        return List.copyOf(found.values());
    }

    private static boolean sameSupplyType(HousingComplex complex, RentalType supplyType) {
        try {
            return supplyType != null && complex.getSupplyType() != null
                    && RentalType.fromStoredValue(complex.getSupplyType()) == supplyType;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }
}
