package com.toadzip.backend.ingest.service;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class MyHomeAnnouncementSourceMapper {

    private static final Map<String, RentalType> RENTAL_TYPES = Map.ofEntries(
            Map.entry("행복주택", RentalType.HAPPY_HOUSING),
            Map.entry("국민임대", RentalType.NATIONAL_RENTAL),
            Map.entry("영구임대", RentalType.PERMANENT_RENTAL),
            Map.entry("50년임대", RentalType.PUBLIC_RENTAL_50Y),
            Map.entry("50년공공임대", RentalType.PUBLIC_RENTAL_50Y),
            Map.entry("통합공공임대", RentalType.INTEGRATED_PUBLIC_RENTAL),
            Map.entry("재개발임대", RentalType.REDEVELOPMENT_RENTAL)
    );

    private static final Map<String, String> COMPLEX_SUPPLY_TYPES = Map.ofEntries(
            Map.entry("행복주택", "HAPPY_HOUSING"),
            Map.entry("국민임대", "NATIONAL_RENTAL"),
            Map.entry("영구임대", "PERMANENT_RENTAL"),
            Map.entry("5년임대", "PUBLIC_RENTAL_5Y"),
            Map.entry("10년임대", "PUBLIC_RENTAL_10Y"),
            Map.entry("50년임대", "PUBLIC_RENTAL_50Y"),
            Map.entry("50년공공임대", "PUBLIC_RENTAL_50Y"),
            Map.entry("통합공공임대", "INTEGRATED_PUBLIC_RENTAL"),
            Map.entry("재개발임대", "REDEVELOPMENT_RENTAL")
    );

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter DOTTED_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd")
            .withResolverStyle(ResolverStyle.STRICT);

    public MyHomeAnnouncementMappingData map(List<MyHomeAnnouncementSource> sources) {
        String identifier = requiredText(sources, MyHomeAnnouncementSource::getPblancId, "공고 식별자");
        String name = requiredText(sources, MyHomeAnnouncementSource::getPblancNm, "공고명");
        String sourceStatus = requiredText(sources, MyHomeAnnouncementSource::getSttusNm, "공고 상태");
        String sourceSupplyType = requiredText(sources, MyHomeAnnouncementSource::getSuplyTyNm, "공급유형");
        String sourceProvider = requiredText(sources, MyHomeAnnouncementSource::getSuplyInsttNm, "공급기관");
        String previousIdentifier = optionalText(
                sources,
                MyHomeAnnouncementSource::getBeforePblancId,
                "이전 공고 식별자"
        );
        String originalUrl = requiredText(sources, this::originalUrlOf, "원문 URL");
        String contact = requiredText(sources, MyHomeAnnouncementSource::getRefrnc, "문의처");
        AnnouncementPublicationType publicationType = publicationTypeOf(sourceStatus, previousIdentifier);
        RentalType rentalType = rentalTypeOf(sourceSupplyType);
        LocalDate postedDate = commonDate(sources, MyHomeAnnouncementSource::getRcritPblancDe, "모집 공고일");
        LocalDate applicationStartDate = commonDate(sources, MyHomeAnnouncementSource::getBeginDe, "모집 시작일");
        LocalDate applicationEndDate = commonDate(sources, MyHomeAnnouncementSource::getEndDe, "모집 종료일");
        LocalDate winnerAnnouncementDate = commonDate(
                sources,
                MyHomeAnnouncementSource::getPrzwnerPresnatnDe,
                "당첨자 발표일"
        );
        validateApplicationPeriod(applicationStartDate, applicationEndDate);
        List<MyHomeSupplyRowMappingData> supplyRows = ordered(sources).stream()
                .map(source -> supplyRowOf(source, name, sourceSupplyType))
                .toList();
        return new MyHomeAnnouncementMappingData(
                identifier,
                previousIdentifier,
                name,
                publicationType,
                rentalType,
                recruitmentTypeOf(name),
                providerOf(sourceProvider),
                postedDate,
                applicationStartDate,
                applicationEndDate,
                winnerAnnouncementDate,
                originalUrl,
                ReceptionPlace.create(sourceProvider, ReceptionMethod.ONLINE, null, contact, originalUrl),
                supplyRows
        );
    }

    private MyHomeSupplyRowMappingData supplyRowOf(
            MyHomeAnnouncementSource source,
            String announcementName,
            String sourceSupplyType
    ) {
        if (source.getHouseSn() == null) {
            throw missing("주택 일련번호");
        }
        String pnu = pnuOf(requiredText(source.getPnu(), "PNU"));
        return new MyHomeSupplyRowMappingData(
                source,
                requiredText(source.getHsmpNm(), "단지명"),
                requiredText(source.getHouseTyNm(), "주택유형명"),
                pnu,
                complexSupplyTypeOf(sourceSupplyType),
                supplyCategoryOf(announcementName),
                nonNegative(source.getSumSuplyCo(), "공급호수")
        );
    }

    private List<MyHomeAnnouncementSource> ordered(List<MyHomeAnnouncementSource> sources) {
        List<MyHomeAnnouncementSource> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparing(
                MyHomeAnnouncementSource::getSourceOrder,
                Comparator.nullsLast(Comparator.naturalOrder())
        ).thenComparing(MyHomeAnnouncementSource::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return ordered;
    }

    private String requiredText(
            List<MyHomeAnnouncementSource> sources,
            Function<MyHomeAnnouncementSource, String> extractor,
            String fieldName
    ) {
        String value = optionalText(sources, extractor, fieldName);
        if (value == null) {
            throw missing(fieldName);
        }
        return value;
    }

    private String optionalText(
            List<MyHomeAnnouncementSource> sources,
            Function<MyHomeAnnouncementSource, String> extractor,
            String fieldName
    ) {
        Set<String> values = new LinkedHashSet<>();
        for (MyHomeAnnouncementSource source : sources) {
            String value = normalizedText(extractor.apply(source));
            if (value != null) {
                values.add(value);
            }
        }
        if (values.size() > 1) {
            throw conflict(fieldName);
        }
        return values.stream().findFirst().orElse(null);
    }

    private String requiredText(String value, String fieldName) {
        String normalized = normalizedText(value);
        if (normalized == null) {
            throw missing(fieldName);
        }
        return normalized;
    }

    private String originalUrlOf(MyHomeAnnouncementSource source) {
        if (normalizedText(source.getUrl()) != null) {
            return normalizedText(source.getUrl());
        }
        if (normalizedText(source.getPcUrl()) != null) {
            return normalizedText(source.getPcUrl());
        }
        return normalizedText(source.getMobileUrl());
    }

    private AnnouncementPublicationType publicationTypeOf(String status, String previousIdentifier) {
        if (status.contains("취소")) {
            requirePreviousIdentifier(previousIdentifier, "취소공고");
            return AnnouncementPublicationType.CANCELLATION;
        }
        if (previousIdentifier != null) {
            return AnnouncementPublicationType.CORRECTION;
        }
        if (status.contains("정정")) {
            throw missing("정정공고의 이전 공고 식별자");
        }
        return AnnouncementPublicationType.ORIGINAL;
    }

    private void requirePreviousIdentifier(String previousIdentifier, String status) {
        if (previousIdentifier == null) {
            throw missing(status + "의 이전 공고 식별자");
        }
    }

    private RentalType rentalTypeOf(String value) {
        return RENTAL_TYPES.getOrDefault(value, RentalType.ETC);
    }

    private String complexSupplyTypeOf(String value) {
        String supplyType = COMPLEX_SUPPLY_TYPES.get(value);
        if (supplyType == null) {
            throw invalid("지원하지 않는 단지 공급유형입니다: " + value);
        }
        return supplyType;
    }

    private AgencyCode providerOf(String value) {
        if (value.startsWith("LH") || value.equals("한국토지주택공사")) {
            return AgencyCode.LH;
        }
        if (Set.of("SH공사", "서울주택도시공사").contains(value)) {
            return AgencyCode.SH;
        }
        if (value.equals("경기주택도시공사")) {
            return AgencyCode.GH;
        }
        return AgencyCode.ETC;
    }

    private RecruitmentType recruitmentTypeOf(String name) {
        if (name.contains("예비")) {
            return RecruitmentType.WAITLIST;
        }
        if (name.contains("모집")) {
            return RecruitmentType.NEW;
        }
        return RecruitmentType.ETC;
    }

    private SupplyCategory supplyCategoryOf(String announcementName) {
        if (announcementName.contains("예비")
                || announcementName.contains("추가")
                || announcementName.contains("재공급")) {
            return SupplyCategory.RESUPPLY;
        }
        return SupplyCategory.NEW_SUPPLY;
    }

    private LocalDate dateOf(String value, String fieldName) {
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
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
    }

    private LocalDate commonDate(
            List<MyHomeAnnouncementSource> sources,
            Function<MyHomeAnnouncementSource, String> extractor,
            String fieldName
    ) {
        return dateOf(requiredText(sources, extractor, fieldName), fieldName);
    }

    private String pnuOf(String pnu) {
        if (pnu.length() != 19 || !pnu.chars().allMatch(Character::isDigit)) {
            throw invalid("PNU는 19자리 숫자여야 합니다.");
        }
        return pnu;
    }

    private Integer nonNegative(Integer value, String fieldName) {
        if (value != null && value < 0) {
            throw invalid(fieldName + "는 음수일 수 없습니다.");
        }
        return value;
    }

    private void validateApplicationPeriod(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw invalid("모집 종료일은 모집 시작일보다 빠를 수 없습니다.");
        }
    }

    private String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private MyHomeAnnouncementMappingRejectedException missing(String fieldName) {
        return new MyHomeAnnouncementMappingRejectedException(
                MyHomeAnnouncementMappingFailureReason.MISSING_REQUIRED_VALUE,
                fieldName + " 값이 없습니다."
        );
    }

    private MyHomeAnnouncementMappingRejectedException conflict(String fieldName) {
        return new MyHomeAnnouncementMappingRejectedException(
                MyHomeAnnouncementMappingFailureReason.CONFLICTING_SOURCE_VALUE,
                "같은 공고의 " + fieldName + " 값이 서로 다릅니다."
        );
    }

    private MyHomeAnnouncementMappingRejectedException invalid(String detail) {
        return new MyHomeAnnouncementMappingRejectedException(
                MyHomeAnnouncementMappingFailureReason.INVALID_VALUE,
                detail
        );
    }
}

record MyHomeAnnouncementMappingData(
        String sourceAnnouncementIdentifier,
        String previousSourceAnnouncementIdentifier,
        String name,
        AnnouncementPublicationType publicationType,
        RentalType rentalType,
        RecruitmentType recruitmentType,
        AgencyCode provider,
        LocalDate postedDate,
        LocalDate applicationStartDate,
        LocalDate applicationEndDate,
        LocalDate winnerAnnouncementDate,
        String originalUrl,
        ReceptionPlace receptionPlace,
        List<MyHomeSupplyRowMappingData> supplyRows
) {
}

record MyHomeSupplyRowMappingData(
        MyHomeAnnouncementSource source,
        String sourceComplexName,
        String sourceHousingTypeName,
        String pnu,
        String complexSupplyType,
        SupplyCategory supplyCategory,
        Integer totalSupplyHouseholdCount
) {
}

class MyHomeAnnouncementMappingRejectedException extends RuntimeException {

    private final MyHomeAnnouncementMappingFailureReason reason;

    MyHomeAnnouncementMappingRejectedException(
            MyHomeAnnouncementMappingFailureReason reason,
            String detail
    ) {
        super(detail);
        this.reason = reason;
    }

    MyHomeAnnouncementMappingFailureReason reason() {
        return reason;
    }
}
