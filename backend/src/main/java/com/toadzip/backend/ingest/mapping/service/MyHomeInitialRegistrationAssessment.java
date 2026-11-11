package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.EXISTING;
import static com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.INFORMATION_REQUIRED;
import static com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.LOCATION_REQUIRED;
import static com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.READY;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.service.RoadAddressGeocodingService;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationLocation;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore.Product;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MyHomeInitialRegistrationAssessment {

    private final MyHomeComplexSourceMapper mapper;
    private final RoadAddressGeocodingService geocoding;
    private final InitialRegistrationInputs inputs;
    private final MyHomeComplexClassificationPolicy classification = new MyHomeComplexClassificationPolicy();

    Assessment assess(InitialRegistrationTarget target, List<MyHomeComplexSourceView> sources,
            InitialRegistrationProductIndex index, Instant now) {
        String identifier = target.getSourceComplexIdentifier();
        Product linked = index.linked(identifier);
        if (linked != null) {
            return existing(linked);
        }
        String preparedAddress = null;
        if (target.getLocation() != null
                && target.getLocation().isAdministratorValueFor(sources.getFirst().getRnAdres())) {
            preparedAddress = target.getLocation().getAddress();
        }
        String preparedName = null;
        if (target.getInformation() != null) {
            preparedName = target.getInformation().getComplexName();
        }
        List<Product> candidates = index.candidates(sources, preparedAddress, preparedName);
        if (!candidates.isEmpty()) {
            Assessment result = candidate(sources, candidates, index);
            if (result.complexId() == null) {
                prepareLocation(target, sources.getFirst().getRnAdres(), now);
            }
            return result;
        }
        prepareLocation(target, sources.getFirst().getRnAdres(), now);
        InitialRegistrationLocation location = target.getLocation();
        if (!location.isReady()) {
            return new Assessment(LOCATION_REQUIRED, locationFailureDetail(location), null);
        }
        var information = target.getInformation();
        if (inputs.hasInformation(information) && !information.matches(inputs.fingerprint(sources))) {
            return new Assessment(INFORMATION_REQUIRED, "원천이 변경되었습니다. 이전 정보 보완값의 근거를 다시 확인하세요.", null);
        }
        try {
            if ("ADMIN".equals(location.getOrigin())) {
                mapper.map(identifier, sources, location.getAddress(), information);
            }
            if (!"ADMIN".equals(location.getOrigin())) {
                mapper.map(identifier, sources, null, information);
            }
            return new Assessment(READY, "필수 정보와 지도용 좌표가 준비되었습니다.", null);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            return new Assessment(INFORMATION_REQUIRED, exception.getMessage(), null);
        }
    }

    private void prepareLocation(InitialRegistrationTarget target, String sourceAddress, Instant now) {
        if (target.getLocation() != null && target.getLocation().isAdministratorValueFor(sourceAddress)) {
            return;
        }
        try {
            var resolved = geocoding.geocode(sourceAddress);
            target.prepareLocation(InitialRegistrationLocation.prepared(sourceAddress, resolved.roadAddress(),
                    resolved.latitude(), resolved.longitude(), false, null, now));
        }
        catch (RoadAddressGeocodingException exception) {
            target.prepareLocation(InitialRegistrationLocation.failed(sourceAddress, exception.getReason(), now));
        }
    }

    private String locationFailureDetail(InitialRegistrationLocation location) {
        return switch (location.getFailure()) {
            case INVALID_ADDRESS -> "주소 누락·형식 오류: 원천 도로명주소를 확인하세요.";
            case ADDRESS_NOT_FOUND -> "주소 불일치: 위치정보요약DB에서 원천 주소를 찾지 못했습니다.";
            case COORDINATE_NOT_FOUND -> "좌표 미제공: 주소는 일치하지만 원본 좌표가 없습니다.";
            case COORDINATE_CONVERSION_ERROR -> "좌표 변환 실패: 지도용 위도·경도를 확인하세요.";
        };
    }

    private Assessment existing(Product product) {
        if (product.deleted()) {
            return new Assessment(INFORMATION_REQUIRED,
                    "휴지통에 있는 기존 단지입니다. 복구 여부를 확인하세요. 단지 ID: " + product.id(), product.id());
        }
        return new Assessment(EXISTING, "서비스에 이미 등록된 단지이므로 건너뜁니다.", product.id());
    }

    private Assessment candidate(List<MyHomeComplexSourceView> sources, List<Product> candidates,
            InitialRegistrationProductIndex index) {
        if (candidates.size() == 1 && same(candidates.getFirst(), sources, index)) {
            return existing(candidates.getFirst());
        }
        String ids = candidates.stream().limit(10).map(product -> Long.toString(product.id()))
                .collect(java.util.stream.Collectors.joining(", "));
        return new Assessment(INFORMATION_REQUIRED,
                "관리자 확인 필요: 같은 단지일 수 있는 기존 후보 " + candidates.size() + "곳. 단지 ID: " + ids, null);
    }

    private boolean same(Product product, List<MyHomeComplexSourceView> sources,
            InitialRegistrationProductIndex index) {
        try {
            MyHomeComplexValueParser parser = new MyHomeComplexValueParser();
            String supplyType = classification.supplyType(
                    parser.requiredText(sources, MyHomeComplexSourceView::getSuplyTyNm, "공급유형"));
            String provider = classification.provider(
                    parser.requiredText(sources, MyHomeComplexSourceView::getInsttNm, "공급기관"));
            return index.same(product, sources, supplyType, provider);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            return false;
        }
    }

    record Assessment(InitialRegistrationStatus status, String detail, Long complexId) {
    }
}
