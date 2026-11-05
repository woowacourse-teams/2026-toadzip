package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.EXISTING;
import static com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.INFORMATION_REQUIRED;
import static com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.LOCATION_REQUIRED;
import static com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.READY;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.service.RoadAddressGeocodingService;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore.Product;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MyHomeInitialRegistrationAssessment {

    private final MyHomeComplexSourceMapper mapper;
    private final RoadAddressGeocodingService geocoding;
    private final MyHomeComplexClassificationPolicy classification = new MyHomeComplexClassificationPolicy();

    Assessment assess(String identifier, List<MyHomeComplexSourceView> sources, InitialRegistrationProductIndex index) {
        Product linked = index.linked(identifier);
        if (linked != null) {
            return existing(linked);
        }
        List<Product> candidates = index.candidates(sources);
        if (!candidates.isEmpty()) {
            return candidate(sources, candidates, index);
        }
        if (sources.stream().anyMatch(source -> source.getRnAdres() == null || source.getRnAdres().isBlank())) {
            return new Assessment(LOCATION_REQUIRED, "원천 도로명주소가 없습니다. 주소·좌표 확인이 필요합니다.", null);
        }
        try {
            var mapped = mapper.map(identifier, sources);
            geocoding.geocode(mapped.address().sourceRoadAddress());
            return new Assessment(READY, "필수 정보와 지도용 좌표가 준비되었습니다.", null);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            return new Assessment(INFORMATION_REQUIRED, exception.getMessage(), null);
        }
        catch (RoadAddressGeocodingException exception) {
            return new Assessment(LOCATION_REQUIRED, exception.getMessage(), null);
        }
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

    private boolean same(Product product, List<MyHomeComplexSourceView> sources, InitialRegistrationProductIndex index) {
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
