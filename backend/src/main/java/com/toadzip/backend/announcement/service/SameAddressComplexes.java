package com.toadzip.backend.announcement.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.location.domain.JusoAddress;
import com.toadzip.backend.ingest.location.domain.RoadNumberAddress;
import com.toadzip.backend.ingest.location.exception.AddressLookupException;
import com.toadzip.backend.ingest.location.repository.external.JusoAddressClient;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * PDF 보정에서 공고 단지와 같은 곳의 기존 단지. LH 정제의 공고 단지 자동 연결(AUTO)과 같은 기준으로, 주소의 (시도, 도로명, 건물번호)와
 * 공급유형이 같은 휴지통 밖 단지다. 주소는 공고문 단지 개요 주소와 원천 도로명주소를 함께 받고, 어느 쪽에서 찾았든 한 번만 센다.
 * 공고문이 건설위치를 지번(학익동 757)으로만 적었으면 주소 검색(도로명주소 API)으로 필지 하나를 찾았을 때만 그 필지(PNU)와 그 필지의
 * 도로명주소로 찾는다. 번지가 없는 주소(동 단위·준공 전 블록)는 같은 곳을 가리킬 수 없어 찾지 않는다. 기관(공급자)은 비교하지 않는다.
 * 같은 단지가 수집 경로에 따라 공급자 '기타'로 저장된 경우가 있어서다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SameAddressComplexes {
    /** 법정동·리·가와 그 뒤의 번지(757, 산 12-3). 번지는 PNU처럼 네 자리까지다. 번지 뒤에 다른 글자(757동)나 블록 표기(3 블록)가 붙으면 지번이 아니다. */
    private static final Pattern LOT_NUMBER = Pattern.compile(
            "([가-힣][가-힣0-9]*(?:동|리|가))\\s+(산\\s*)?([0-9]{1,4})(?:-([0-9]{1,4}))?(?![0-9가-힣-])(?!\\s*(?:블록|BL|단지|호))");

    private final HousingComplexRepository complexes;
    private final JusoAddressClient juso;

    /** 후보가 정확히 하나일 때만 관리자 선택 없이 잇는다. 둘 이상이면 관리자가 고른다. */
    List<HousingComplex> find(Collection<String> addresses, RentalType supplyType) {
        Map<Long, HousingComplex> found = new LinkedHashMap<>();
        addresses.stream().filter(Objects::nonNull).distinct().forEach(address -> {
            Optional<RoadNumberAddress> road = RoadNumberAddress.parse(address);
            if (road.isPresent()) {
                byRoad(road.get(), supplyType).forEach(complex -> found.putIfAbsent(complex.getId(), complex));
                return;
            }
            lotParcel(address).ifPresent(parcel -> {
                complexes.findAllByPnu(parcel.pnu()).stream().filter(complex -> sameSupplyType(complex, supplyType))
                        .forEach(complex -> found.putIfAbsent(complex.getId(), complex));
                RoadNumberAddress.parse(parcel.roadAddress()).ifPresent(key -> byRoad(key, supplyType)
                        .forEach(complex -> found.putIfAbsent(complex.getId(), complex)));
            });
        });
        return List.copyOf(found.values());
    }

    private List<HousingComplex> byRoad(RoadNumberAddress key, RentalType supplyType) {
        return complexes.findAllActiveByRoad(key.road()).stream()
                .filter(complex -> sameSupplyType(complex, supplyType) && complex.getAddress() != null
                        && RoadNumberAddress.parse(complex.getAddress().getRoadAddress()).equals(Optional.of(key)))
                .toList();
    }

    /**
     * 지번 주소가 가리키는 필지. 시도부터 적은 주소만 검색하고, 같은 시도·동·번지의 필지가 하나일 때만 쓴다. 여럿이면 어느 곳인지 모른다.
     * 이 검색은 자동 공개 판정 트랜잭션 안에서도 불려 응답을 기다리는 동안(연결 2초·응답 3초 제한) 공고 행 잠금을 쥔다.
     */
    private Optional<JusoAddress> lotParcel(String address) {
        var lot = LOT_NUMBER.matcher(address);
        Optional<String> province = RoadNumberAddress.province(address);
        if (province.isEmpty() || !lot.find()) {
            return Optional.empty();
        }
        String dong = lot.group(1);
        boolean mountain = lot.group(2) != null;
        int main = Integer.parseInt(lot.group(3));
        int sub = lot.group(4) == null ? 0 : Integer.parseInt(lot.group(4));
        try {
            // 검색은 비슷한 번지(757-1)·다른 지역의 같은 동 이름도 돌려주므로 적은 시도·동·번지와 같은 필지만 남긴다.
            var parcels = juso.search(address.strip(), 5).stream()
                    .filter(found -> found.legalDongCode() != null && found.legalDongCode().matches("\\d{10}")
                            && found.jibunAddress() != null && province.equals(RoadNumberAddress.province(found.jibunAddress()))
                            && found.jibunAddress().contains(dong)
                            && found.mountain() == mountain && found.lotMainNumber() == main && found.lotSubNumber() == sub)
                    .toList();
            return parcels.stream().map(JusoAddress::pnu).distinct().count() == 1
                    ? Optional.of(parcels.getFirst()) : Optional.empty();
        } catch (AddressLookupException | RestClientException unavailable) {
            // 검색을 쓸 수 없으면(키 없음·장애·응답 형식 오류) 찾지 않고 관리자가 고르게 둔다.
            log.warn("event=same_address.lot_lookup.failed provider=juso reason={}", unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
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
