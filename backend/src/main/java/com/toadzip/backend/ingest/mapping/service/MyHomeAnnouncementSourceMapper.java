package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MyHomeAnnouncementSourceMapper {

    private final MyHomeAnnouncementValueParser parser = new MyHomeAnnouncementValueParser();
    private final MyHomeAnnouncementClassificationPolicy classification =
            new MyHomeAnnouncementClassificationPolicy(parser);
    private final MyHomeAnnouncementCommonValuesMapper commonValuesMapper;

    public MyHomeAnnouncementSourceMapper(MyHomeAnnouncementCommonValuesMapper commonValuesMapper) {
        this.commonValuesMapper = commonValuesMapper;
    }

    public MyHomeAnnouncementMappingData map(List<MyHomeAnnouncementSource> sources) {
        MyHomeAnnouncementCommonValues common = commonValuesMapper.map(sources);
        List<MyHomeSupplyRowMappingData> supplyRows = ordered(sources).stream()
                .map(source -> supplyRowOf(source, common.name(), common.sourceSupplyType()))
                .toList();
        return new MyHomeAnnouncementMappingData(
                common.identifier(),
                common.previousIdentifier(),
                common.name(),
                common.publicationType(),
                common.rentalType(),
                classification.recruitmentType(common.name()),
                classification.provider(common.sourceProvider()),
                common.postedDate(),
                common.applicationStartDate(),
                common.applicationEndDate(),
                common.winnerAnnouncementDate(),
                common.originalUrl(),
                ReceptionPlace.create(common.sourceProvider(), ReceptionMethod.ONLINE, null,
                        common.contact(), common.originalUrl()),
                supplyRows,
                false
        );
    }

    private MyHomeSupplyRowMappingData supplyRowOf(
            MyHomeAnnouncementSource source,
            String announcementName,
            String sourceSupplyType
    ) {
        if (source.getHouseSn() == null) {
            throw parser.missing("주택 일련번호");
        }
        String pnu = parser.pnu(parser.requiredText(source.getPnu(), "PNU"));
        return new MyHomeSupplyRowMappingData(
                source,
                source.getSourceKey(),
                parser.requiredText(source.getHsmpNm(), "단지명"),
                parser.requiredText(source.getHouseTyNm(), "주택유형명"),
                pnu,
                classification.complexSupplyType(sourceSupplyType),
                classification.supplyCategory(announcementName),
                parser.nonNegative(source.getSumSuplyCo(), "공급호수"),
                null,
                null,
                null,
                null
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
        List<MyHomeSupplyRowMappingData> supplyRows,
        boolean preserveExistingLhResolvedRows
) {

    MyHomeAnnouncementMappingData withSupplyRows(List<MyHomeSupplyRowMappingData> resolvedSupplyRows) {
        return new MyHomeAnnouncementMappingData(
                sourceAnnouncementIdentifier,
                previousSourceAnnouncementIdentifier,
                name,
                publicationType,
                rentalType,
                recruitmentType,
                provider,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                winnerAnnouncementDate,
                originalUrl,
                receptionPlace,
                resolvedSupplyRows,
                false
        );
    }

    MyHomeAnnouncementMappingData preservingExistingLhResolvedRows() {
        return new MyHomeAnnouncementMappingData(
                sourceAnnouncementIdentifier,
                previousSourceAnnouncementIdentifier,
                name,
                publicationType,
                rentalType,
                recruitmentType,
                provider,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                winnerAnnouncementDate,
                originalUrl,
                receptionPlace,
                supplyRows,
                true
        );
    }
}

record MyHomeSupplyRowMappingData(
        MyHomeAnnouncementSource source,
        String sourceSupplyRowIdentifier,
        String sourceComplexName,
        String sourceHousingTypeName,
        String pnu,
        String complexSupplyType,
        SupplyCategory supplyCategory,
        Integer totalSupplyHouseholdCount,
        BigDecimal exclusiveArea,
        BigDecimal supplyArea,
        Integer lhTotalSupplyHouseholdCount,
        String resolvedLhSourceIdentifier
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
