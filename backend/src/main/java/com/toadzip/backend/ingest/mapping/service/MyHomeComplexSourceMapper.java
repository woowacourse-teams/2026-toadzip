package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.MyHomeRentalCondition;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.domain.SourceArea;
import com.toadzip.backend.ingest.location.domain.GeocodedRoadAddress;
import com.toadzip.backend.ingest.location.domain.NormalizedRoadAddress;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class MyHomeComplexSourceMapper {

    private final MyHomeComplexValueParser parser = new MyHomeComplexValueParser();
    private final MyHomeComplexClassificationPolicy classification = new MyHomeComplexClassificationPolicy();

    public MyHomeComplexMappingData map(String sourceComplexIdentifier, List<MyHomeComplexSourceView> sources) {
        String name = parser.requiredText(sources, MyHomeComplexSourceView::getHsmpNm, "단지명");
        String supplyType = classification.supplyType(
                parser.requiredText(sources, MyHomeComplexSourceView::getSuplyTyNm, "공급유형")
        );
        String roadAddress = parser.requiredText(sources, MyHomeComplexSourceView::getRnAdres, "도로명주소");
        String pnu = parser.pnu(parser.requiredText(sources, MyHomeComplexSourceView::getPnu, "PNU"));
        String provinceCode = parser.provinceCode(
                parser.requiredText(sources, MyHomeComplexSourceView::getBrtcCode, "시·도 코드")
        );
        String districtCode = parser.districtCode(
                provinceCode,
                parser.requiredText(sources, MyHomeComplexSourceView::getSignguCode, "시·군·구 코드")
        );
        int totalHouseholdCount = parser.requiredNonNegativeInteger(
                sources,
                MyHomeComplexSourceView::getHshldCo,
                "전체 세대수"
        );
        String provider = classification.provider(
                parser.requiredText(sources, MyHomeComplexSourceView::getInsttNm, "공급기관")
        );
        LocalDate completionDate = parser.optionalDate(
                parser.optionalText(sources, MyHomeComplexSourceView::getCompetDe, "준공일")
        );
        String heatingType = classification.heatingType(
                parser.optionalText(sources, MyHomeComplexSourceView::getHeatMthdDetailNm, "난방방식")
        );
        String housingType = classification.housingType(
                parser.optionalText(sources, MyHomeComplexSourceView::getHouseTyNm, "주택유형")
        );
        String corridorType = classification.corridorType(
                parser.optionalText(sources, MyHomeComplexSourceView::getBuldStleNm, "복도유형")
        );
        Boolean elevatorInstalled = classification.elevatorInstalled(
                parser.optionalText(sources, MyHomeComplexSourceView::getElvtrInstlAtNm, "승강기 설치 여부")
        );
        int parkingSpaceCount = parser.requiredNonNegativeInteger(
                sources,
                MyHomeComplexSourceView::getParkngCo,
                "주차대수"
        );
        MyHomeAddressMappingData address = new MyHomeAddressMappingData(
                roadAddress,
                pnu,
                pnu.substring(0, 10),
                provinceCode,
                districtCode
        );
        List<MyHomeHousingTypeMappingData> housingTypes = sources.stream()
                .map(this::housingTypeOf)
                .toList();
        return new MyHomeComplexMappingData(
                sourceComplexIdentifier,
                name,
                supplyType,
                address,
                totalHouseholdCount,
                provider,
                completionDate,
                heatingType,
                housingType,
                corridorType,
                elevatorInstalled,
                parkingSpaceCount,
                housingTypes
        );
    }

    public String sourceComplexIdentifier(MyHomeComplexSourceView source) {
        if (source.getHsmpSn() == null) {
            throw parser.missing("단지 식별자");
        }
        String supplyType = classification.sourceCode(parser.requiredText(source.getSuplyTyNm(), "공급유형"));
        return source.getHsmpSn() + ":" + supplyType;
    }

    public boolean shouldSkip(MyHomeComplexSourceView source) {
        String supplyType = parser.normalizedText(source.getSuplyTyNm());
        if ("매입임대".equals(supplyType) || "매입임대".equals(parser.normalizedText(source.getStyleNm()))) {
            return true;
        }
        // 다가구주택 장기전세는 월임대료가 있는 매입형이다. 아파트 장기전세는 건설임대로 유지한다.
        return "장기전세".equals(supplyType) && "다가구주택".equals(parser.normalizedText(source.getHouseTyNm()));
    }

    private MyHomeHousingTypeMappingData housingTypeOf(MyHomeComplexSourceView source) {
        String name = parser.requiredText(source.getStyleNm(), "주택형명");
        BigDecimal exclusiveArea = parser.requiredArea(source.getSuplyPrvuseAr(), "전용면적");
        BigDecimal commonArea = parser.optionalArea(source.getSuplyCmnuseAr(), "공용면적");
        BigDecimal supplyArea = null;
        if (commonArea != null) {
            supplyArea = SourceArea.normalized(exclusiveArea.add(commonArea));
        }
        return new MyHomeHousingTypeMappingData(
                source.getSourceKey(),
                name,
                exclusiveArea,
                supplyArea,
                source.getCollectedAt() == null ? null : new MyHomeRentalCondition(
                        nonNegativeAmount(source.getBassRentGtn()),
                        nonNegativeAmount(source.getBassMtRntchrg()),
                        source.getCollectedAt())
        );
    }

    private Long nonNegativeAmount(Long amount) {
        if (amount == null || amount < 0) {
            return null;
        }
        return amount;
    }

    record MyHomeComplexMappingData(
            String sourceComplexIdentifier,
            String name,
            String supplyType,
            MyHomeAddressMappingData address,
            int totalHouseholdCount,
            String provider,
            LocalDate completionDate,
            String heatingType,
            String housingType,
            String corridorType,
            Boolean elevatorInstalled,
            int parkingSpaceCount,
            List<MyHomeHousingTypeMappingData> housingTypes
    ) {

        SplitComplexKey splitKey() {
            return new SplitComplexKey(name, supplyType, provider, address.pnu(), address.provinceCode(),
                    address.cityCountyDistrictCode(), new NormalizedRoadAddress(address.sourceRoadAddress()).value(),
                    completionDate, heatingType, housingType, corridorType, elevatorInstalled, parkingSpaceCount);
        }

        static MyHomeComplexMappingData combine(List<MyHomeComplexMappingData> members) {
            MyHomeComplexMappingData first = members.getFirst();
            return new MyHomeComplexMappingData(
                    first.sourceComplexIdentifier,
                    first.name,
                    first.supplyType,
                    first.address,
                    members.stream().mapToInt(MyHomeComplexMappingData::totalHouseholdCount).sum(),
                    first.provider,
                    first.completionDate,
                    first.heatingType,
                    first.housingType,
                    first.corridorType,
                    first.elevatorInstalled,
                    first.parkingSpaceCount,
                    members.stream().flatMap(member -> member.housingTypes.stream()).toList()
            );
        }

        boolean matchesVerifiedProduct(HousingComplex product) {
            return name.equals(product.getName()) && provider.equals(product.getProvider())
                    && supplyType.equals(product.getSupplyType())
                    && address.pnu().equals(product.getAddress().getPnu())
                    && address.provinceCode().equals(product.getAddress().getProvinceCode())
                    && address.cityCountyDistrictCode().equals(product.getAddress().getCityCountyDistrictCode())
                    && new NormalizedRoadAddress(address.sourceRoadAddress())
                            .matches(product.getAddress().getRoadAddress())
                    && Objects.equals(completionDate, product.getCompletionDate())
                    && Objects.equals(heatingType, product.getHeatingType())
                    && Objects.equals(housingType, product.getHousingType())
                    && Objects.equals(corridorType, product.getCorridorType())
                    && Objects.equals(elevatorInstalled, product.getElevatorInstalled())
                    && Objects.equals(parkingSpaceCount, product.getParkingSpaceCount());
        }
    }

    record SplitComplexKey(
            String name,
            String supplyType,
            String provider,
            String pnu,
            String provinceCode,
            String cityCountyDistrictCode,
            String roadAddress,
            LocalDate completionDate,
            String heatingType,
            String housingType,
            String corridorType,
            Boolean elevatorInstalled,
            int parkingSpaceCount
    ) {
    }

    record MyHomeAddressMappingData(
            String sourceRoadAddress,
            String pnu,
            String legalDongCode,
            String provinceCode,
            String cityCountyDistrictCode
    ) {

        Address resolve(GeocodedRoadAddress geocodedAddress) {
            return Address.create(
                    geocodedAddress.roadAddress(),
                    pnu,
                    legalDongCode,
                    provinceCode,
                    cityCountyDistrictCode,
                    geocodedAddress.latitude(),
                    geocodedAddress.longitude()
            );
        }
    }

    record MyHomeHousingTypeMappingData(
            String sourceHousingTypeIdentifier,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea,
            MyHomeRentalCondition basicRentalCondition
    ) {
    }
}
