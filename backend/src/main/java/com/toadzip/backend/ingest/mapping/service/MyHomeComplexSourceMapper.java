package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.location.domain.GeocodedRoadAddress;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MyHomeComplexSourceMapper {

    private final MyHomeComplexValueParser parser = new MyHomeComplexValueParser();
    private final MyHomeComplexClassificationPolicy classification = new MyHomeComplexClassificationPolicy();

    public MyHomeComplexMappingData map(String sourceComplexIdentifier, List<MyHomeComplexSource> sources) {
        String name = parser.requiredText(sources, MyHomeComplexSource::getHsmpNm, "단지명");
        String supplyType = classification.supplyType(
                parser.requiredText(sources, MyHomeComplexSource::getSuplyTyNm, "공급유형")
        );
        String roadAddress = parser.requiredText(sources, MyHomeComplexSource::getRnAdres, "도로명주소");
        String pnu = parser.pnu(parser.requiredText(sources, MyHomeComplexSource::getPnu, "PNU"));
        String provinceCode = parser.provinceCode(
                parser.requiredText(sources, MyHomeComplexSource::getBrtcCode, "시·도 코드")
        );
        String districtCode = parser.districtCode(
                provinceCode,
                parser.requiredText(sources, MyHomeComplexSource::getSignguCode, "시·군·구 코드")
        );
        int totalHouseholdCount = parser.requiredNonNegativeInteger(
                sources,
                MyHomeComplexSource::getHshldCo,
                "전체 세대수"
        );
        String provider = classification.provider(
                parser.requiredText(sources, MyHomeComplexSource::getInsttNm, "공급기관")
        );
        LocalDate completionDate = parser.optionalDate(
                parser.optionalText(sources, MyHomeComplexSource::getCompetDe, "준공일")
        );
        String heatingType = classification.heatingType(
                parser.optionalText(sources, MyHomeComplexSource::getHeatMthdDetailNm, "난방방식")
        );
        String housingType = classification.housingType(
                parser.optionalText(sources, MyHomeComplexSource::getHouseTyNm, "주택유형")
        );
        String corridorType = classification.corridorType(
                parser.optionalText(sources, MyHomeComplexSource::getBuldStleNm, "복도유형")
        );
        Boolean elevatorInstalled = classification.elevatorInstalled(
                parser.optionalText(sources, MyHomeComplexSource::getElvtrInstlAtNm, "승강기 설치 여부")
        );
        int parkingSpaceCount = parser.requiredNonNegativeInteger(
                sources,
                MyHomeComplexSource::getParkngCo,
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

    public String sourceComplexIdentifier(MyHomeComplexSource source) {
        if (source.getHsmpSn() == null) {
            throw parser.missing("단지 식별자");
        }
        String supplyType = classification.supplyType(parser.requiredText(source.getSuplyTyNm(), "공급유형"));
        return source.getHsmpSn() + ":" + supplyType;
    }

    public boolean shouldSkip(MyHomeComplexSource source) {
        String supplyType = parser.normalizedText(source.getSuplyTyNm());
        return "매입임대".equals(supplyType);
    }

    private MyHomeHousingTypeMappingData housingTypeOf(MyHomeComplexSource source) {
        String name = parser.requiredText(source.getStyleNm(), "주택형명");
        BigDecimal exclusiveArea = parser.requiredArea(source.getSuplyPrvuseAr(), "전용면적");
        BigDecimal commonArea = parser.optionalArea(source.getSuplyCmnuseAr(), "공용면적");
        BigDecimal supplyArea = null;
        if (commonArea != null) {
            supplyArea = exclusiveArea.add(commonArea).setScale(4, RoundingMode.HALF_UP);
        }
        return new MyHomeHousingTypeMappingData(
                source.getSourceKey(),
                name,
                exclusiveArea,
                supplyArea
        );
    }

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
        BigDecimal supplyArea
) {
}

class MyHomeComplexMappingRejectedException extends RuntimeException {

    private final MyHomeComplexMappingFailureReason reason;

    MyHomeComplexMappingRejectedException(MyHomeComplexMappingFailureReason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    MyHomeComplexMappingFailureReason reason() {
        return reason;
    }
}
