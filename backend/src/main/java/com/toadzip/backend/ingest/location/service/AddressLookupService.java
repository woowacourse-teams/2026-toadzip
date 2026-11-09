package com.toadzip.backend.ingest.location.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.location.domain.AddressKeywordMatch;
import com.toadzip.backend.ingest.location.domain.JusoAddress;
import com.toadzip.backend.ingest.location.domain.RoadNumberAddress;
import com.toadzip.backend.ingest.location.domain.Wgs84Coordinate;
import com.toadzip.backend.ingest.location.dto.AddressCandidate;
import com.toadzip.backend.ingest.location.dto.RegisteredComplex;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.repository.external.JusoAddressClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 관리자가 단지를 등록할 때 주소 하나로 PNU·행정구역 코드·좌표를 채우고, 같은 곳에 이미 등록된 단지를 함께 알려 준다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressLookupService {

    private static final int MAX_CANDIDATES = 5;

    private final JusoAddressClient client;
    private final UtmKCoordinateConverter converter;
    private final HousingComplexRepository complexes;

    public List<AddressCandidate> lookup(String keyword) {
        return lookup(keyword, null);
    }

    /** sourcePnu는 원천행(LH)·마이홈이 알려 준 필지다. 등록 화면이 그 필지를 유지할 수 있어 그 필지의 단지도 함께 찾는다. */
    public List<AddressCandidate> lookup(String keyword, String sourcePnu) {
        String query = keyword.strip();
        String knownPnu = sourcePnu != null && sourcePnu.matches("\\d{19}") ? sourcePnu : null;
        return client.search(query, MAX_CANDIDATES).stream()
                .filter(address -> address.legalDongCode().matches("\\d{10}"))
                .map(address -> candidate(query, knownPnu, address)).toList();
    }

    private AddressCandidate candidate(String query, String sourcePnu, JusoAddress address) {
        Wgs84Coordinate coordinate = coordinate(address);
        String code = address.legalDongCode();
        return new AddressCandidate(address.roadAddress(), address.jibunAddress(), address.pnu(), code,
                code.substring(0, 2), code.substring(0, 5),
                coordinate == null ? null : coordinate.latitude(), coordinate == null ? null : coordinate.longitude(),
                AddressKeywordMatch.matches(query, address), registered(address, sourcePnu));
    }

    /** 같은 필지이거나 같은 (시도, 도로명, 건물번호)인 단지. 저장 주소가 구를 빼거나 시도를 줄여 써도 같은 곳으로 찾는다. */
    private List<RegisteredComplex> registered(JusoAddress address, String sourcePnu) {
        Map<Long, HousingComplex> found = new LinkedHashMap<>();
        complexes.findAllByPnu(address.pnu()).forEach(complex -> found.putIfAbsent(complex.getId(), complex));
        // 같은 법정동이면 화면이 원천 필지를 유지하므로 그 필지의 단지도 같은 곳이다.
        if (sourcePnu != null && sourcePnu.startsWith(address.legalDongCode())) {
            complexes.findAllByPnu(sourcePnu).forEach(complex -> found.putIfAbsent(complex.getId(), complex));
        }
        String provinceCode = address.legalDongCode().substring(0, 2);
        RoadNumberAddress.parse(address.roadAddress()).ifPresent(road -> complexes.findAllActiveByRoadName(provinceCode, road.road()).stream()
                .filter(complex -> complex.getAddress() != null && sameCity(complex.getAddress().getCityCountyDistrictCode(), address)
                        && RoadNumberAddress.parse(complex.getAddress().getRoadAddress()).equals(Optional.of(road)))
                .forEach(complex -> found.putIfAbsent(complex.getId(), complex)));
        return found.values().stream().map(complex -> new RegisteredComplex(complex.getId(), complex.getName(),
                complex.getAddress() == null ? null : complex.getAddress().getRoadAddress(), rentalType(complex.getSupplyType()))).toList();
    }

    /**
     * 도로명은 시 안에서만 고유하다. 시군구 코드 앞 네 자리(시·도 두 자리 + 시)가 같으면 같은 시로 본다.
     * 화성시(41590)와 그 아래 동탄구(41597)처럼 일반구가 생기거나 빠진 표기는 같은 시로 남는다.
     */
    private static boolean sameCity(String cityCountyDistrictCode, JusoAddress address) {
        return cityCountyDistrictCode != null && cityCountyDistrictCode.length() >= 4
                && address.legalDongCode().startsWith(cityCountyDistrictCode.substring(0, 4));
    }

    private static String rentalType(String stored) {
        try {
            return stored == null ? null : RentalType.fromStoredValue(stored).name();
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** 좌표는 선택값이라 변환에 실패한 후보도 좌표 없이 돌려준다. 주소는 개인정보일 수 있어 남기지 않는다. */
    private Wgs84Coordinate coordinate(JusoAddress address) {
        try {
            return client.coordinate(address).map(converter::convert).orElse(null);
        } catch (RoadAddressGeocodingException exception) {
            log.warn("event=address_lookup.coordinate.failed provider=juso reason={}", exception.getReason());
            return null;
        }
    }
}
