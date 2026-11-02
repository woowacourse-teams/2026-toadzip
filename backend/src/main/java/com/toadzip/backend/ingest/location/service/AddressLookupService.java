package com.toadzip.backend.ingest.location.service;

import com.toadzip.backend.ingest.location.domain.JusoAddress;
import com.toadzip.backend.ingest.location.domain.Wgs84Coordinate;
import com.toadzip.backend.ingest.location.dto.AddressCandidate;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.repository.external.JusoAddressClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 관리자가 단지를 등록할 때 주소 하나로 PNU·행정구역 코드·좌표를 채운다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressLookupService {

    private static final int MAX_CANDIDATES = 5;

    private final JusoAddressClient client;
    private final UtmKCoordinateConverter converter;

    public List<AddressCandidate> lookup(String keyword) {
        return client.search(keyword.strip(), MAX_CANDIDATES).stream()
                .filter(address -> address.legalDongCode().matches("\\d{10}"))
                .map(this::candidate).toList();
    }

    private AddressCandidate candidate(JusoAddress address) {
        Wgs84Coordinate coordinate = coordinate(address);
        String code = address.legalDongCode();
        return new AddressCandidate(address.roadAddress(), address.jibunAddress(), address.pnu(), code,
                code.substring(0, 2), code.substring(0, 5),
                coordinate == null ? null : coordinate.latitude(), coordinate == null ? null : coordinate.longitude());
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
