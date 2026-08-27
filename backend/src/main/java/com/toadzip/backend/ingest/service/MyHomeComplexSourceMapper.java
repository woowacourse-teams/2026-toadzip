package com.toadzip.backend.ingest.service;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.domain.MyHomeComplexSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class MyHomeComplexSourceMapper {

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter DOTTED_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd")
            .withResolverStyle(ResolverStyle.STRICT);

    public MyHomeComplexMappingData map(String sourceComplexIdentifier, List<MyHomeComplexSource> sources) {
        String name = requiredText(sources, MyHomeComplexSource::getHsmpNm, "단지명");
        String supplyType = requiredText(sources, MyHomeComplexSource::getSuplyTyNm, "공급유형");
        String roadAddress = requiredText(sources, MyHomeComplexSource::getRnAdres, "도로명주소");
        String pnu = pnuOf(requiredText(sources, MyHomeComplexSource::getPnu, "PNU"));
        String provinceCode = provinceCodeOf(requiredText(sources, MyHomeComplexSource::getBrtcCode, "시·도 코드"));
        String districtCode = districtCodeOf(
                provinceCode,
                requiredText(sources, MyHomeComplexSource::getSignguCode, "시·군·구 코드")
        );
        int totalHouseholdCount = requiredNonNegativeInteger(
                sources,
                MyHomeComplexSource::getHshldCo,
                "전체 세대수"
        );
        String provider = requiredText(sources, MyHomeComplexSource::getInsttNm, "공급기관");
        LocalDate completionDate = dateOf(requiredText(sources, MyHomeComplexSource::getCompetDe, "준공일"));
        String heatingType = requiredText(sources, MyHomeComplexSource::getHeatMthdDetailNm, "난방방식");
        String housingType = requiredText(sources, MyHomeComplexSource::getHouseTyNm, "주택유형");
        String corridorType = requiredText(sources, MyHomeComplexSource::getBuldStleNm, "복도유형");
        boolean elevatorInstalled = elevatorInstalledOf(
                requiredText(sources, MyHomeComplexSource::getElvtrInstlAtNm, "승강기 설치 여부")
        );
        int parkingSpaceCount = requiredNonNegativeInteger(
                sources,
                MyHomeComplexSource::getParkngCo,
                "주차대수"
        );
        Address address = Address.createFromMyHome(
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

    private MyHomeHousingTypeMappingData housingTypeOf(MyHomeComplexSource source) {
        String name = requiredText(source.getStyleNm(), "주택형명");
        BigDecimal exclusiveArea = requiredArea(source.getSuplyPrvuseAr(), "전용면적");
        BigDecimal commonArea = optionalArea(source.getSuplyCmnuseAr(), "공용면적");
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

    private String requiredText(
            List<MyHomeComplexSource> sources,
            Function<MyHomeComplexSource, String> extractor,
            String fieldName
    ) {
        Set<String> values = new LinkedHashSet<>();
        for (MyHomeComplexSource source : sources) {
            String value = normalizedText(extractor.apply(source));
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            throw missing(fieldName);
        }
        if (values.size() > 1) {
            throw conflict(fieldName);
        }
        return values.iterator().next();
    }

    private int requiredNonNegativeInteger(
            List<MyHomeComplexSource> sources,
            Function<MyHomeComplexSource, Integer> extractor,
            String fieldName
    ) {
        Set<Integer> values = new LinkedHashSet<>();
        for (MyHomeComplexSource source : sources) {
            Integer value = extractor.apply(source);
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            throw missing(fieldName);
        }
        if (values.size() > 1) {
            throw conflict(fieldName);
        }
        int value = values.iterator().next();
        if (value < 0) {
            throw invalid(fieldName + "은 음수일 수 없습니다.");
        }
        return value;
    }

    private String requiredText(String value, String fieldName) {
        String normalized = normalizedText(value);
        if (normalized == null) {
            throw missing(fieldName);
        }
        return normalized;
    }

    private BigDecimal requiredArea(BigDecimal value, String fieldName) {
        if (value == null) {
            throw missing(fieldName);
        }
        return normalizedArea(value, fieldName);
    }

    private BigDecimal optionalArea(BigDecimal value, String fieldName) {
        if (value == null) {
            return null;
        }
        return normalizedArea(value, fieldName);
    }

    private BigDecimal normalizedArea(BigDecimal value, String fieldName) {
        if (value.signum() < 0) {
            throw invalid(fieldName + "은 음수일 수 없습니다.");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private String pnuOf(String pnu) {
        if (pnu.length() != 19 || !pnu.chars().allMatch(Character::isDigit)) {
            throw invalid("PNU는 19자리 숫자여야 합니다.");
        }
        return pnu;
    }

    private String provinceCodeOf(String provinceCode) {
        if (provinceCode.length() != 2 || !provinceCode.chars().allMatch(Character::isDigit)) {
            throw invalid("시·도 코드는 2자리 숫자여야 합니다.");
        }
        return provinceCode;
    }

    private String districtCodeOf(String provinceCode, String districtCode) {
        String normalized = districtCode;
        if (districtCode.length() == 3) {
            normalized = provinceCode + districtCode;
        }
        if (normalized.length() != 5
                || !normalized.startsWith(provinceCode)
                || !normalized.chars().allMatch(Character::isDigit)) {
            throw invalid("시·군·구 코드는 시·도 코드를 포함한 5자리 숫자여야 합니다.");
        }
        return normalized;
    }

    private LocalDate dateOf(String value) {
        DateTimeFormatter formatter = COMPACT_DATE;
        if (value.contains(".")) {
            formatter = DOTTED_DATE;
        }
        if (value.contains("-")) {
            formatter = DateTimeFormatter.ISO_LOCAL_DATE;
        }
        try {
            return LocalDate.parse(value, formatter);
        }
        catch (DateTimeParseException exception) {
            throw invalid("준공일 형식이 올바르지 않습니다.");
        }
    }

    private boolean elevatorInstalledOf(String value) {
        if (Set.of("전체동 설치", "일부동 설치", "설치", "Y", "예").contains(value)) {
            return true;
        }
        if (Set.of("미설치", "N", "아니오").contains(value)) {
            return false;
        }
        throw invalid("승강기 설치 여부 값이 올바르지 않습니다.");
    }

    private String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private MyHomeComplexMappingRejectedException missing(String fieldName) {
        return new MyHomeComplexMappingRejectedException(
                MyHomeComplexMappingFailureReason.MISSING_REQUIRED_VALUE,
                fieldName + " 값이 없습니다."
        );
    }

    private MyHomeComplexMappingRejectedException conflict(String fieldName) {
        return new MyHomeComplexMappingRejectedException(
                MyHomeComplexMappingFailureReason.CONFLICTING_SOURCE_VALUE,
                "같은 단지의 " + fieldName + " 값이 서로 다릅니다."
        );
    }

    private MyHomeComplexMappingRejectedException invalid(String detail) {
        return new MyHomeComplexMappingRejectedException(
                MyHomeComplexMappingFailureReason.INVALID_VALUE,
                detail
        );
    }
}

record MyHomeComplexMappingData(
        String sourceComplexIdentifier,
        String name,
        String supplyType,
        Address address,
        int totalHouseholdCount,
        String provider,
        LocalDate completionDate,
        String heatingType,
        String housingType,
        String corridorType,
        boolean elevatorInstalled,
        int parkingSpaceCount,
        List<MyHomeHousingTypeMappingData> housingTypes
) {
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
