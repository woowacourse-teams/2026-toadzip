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

    public MyHomeAnnouncementMappingData map(List<MyHomeAnnouncementSource> sources) {
        String identifier = parser.requiredText(sources, MyHomeAnnouncementSource::getPblancId, "공고 식별자");
        String name = parser.requiredText(sources, MyHomeAnnouncementSource::getPblancNm, "공고명");
        String sourceStatus = parser.requiredText(sources, MyHomeAnnouncementSource::getSttusNm, "공고 상태");
        String sourceSupplyType = parser.requiredText(
                sources,
                MyHomeAnnouncementSource::getSuplyTyNm,
                "공급유형"
        );
        String sourceProvider = parser.requiredText(
                sources,
                MyHomeAnnouncementSource::getSuplyInsttNm,
                "공급기관"
        );
        String previousIdentifier = parser.optionalText(
                sources,
                MyHomeAnnouncementSource::getBeforePblancId,
                "이전 공고 식별자"
        );
        String originalUrl = parser.requiredText(sources, this::originalUrlOf, "원문 URL");
        String contact = parser.optionalText(sources, MyHomeAnnouncementSource::getRefrnc, "문의처");
        AnnouncementPublicationType publicationType = classification.publicationType(sourceStatus, previousIdentifier);
        RentalType rentalType = classification.rentalType(sourceSupplyType);
        LocalDate postedDate = parser.commonDate(
                sources,
                MyHomeAnnouncementSource::getRcritPblancDe,
                "모집 공고일"
        );
        LocalDate applicationStartDate = parser.commonDate(
                sources,
                MyHomeAnnouncementSource::getBeginDe,
                "모집 시작일"
        );
        LocalDate applicationEndDate = parser.commonDate(
                sources,
                MyHomeAnnouncementSource::getEndDe,
                "모집 종료일"
        );
        LocalDate winnerAnnouncementDate = parser.commonDate(
                sources,
                MyHomeAnnouncementSource::getPrzwnerPresnatnDe,
                "당첨자 발표일"
        );
        parser.validateApplicationPeriod(applicationStartDate, applicationEndDate);
        List<MyHomeSupplyRowMappingData> supplyRows = ordered(sources).stream()
                .map(source -> supplyRowOf(source, name, sourceSupplyType))
                .toList();
        return new MyHomeAnnouncementMappingData(
                identifier,
                previousIdentifier,
                name,
                publicationType,
                rentalType,
                classification.recruitmentType(name),
                classification.provider(sourceProvider),
                postedDate,
                applicationStartDate,
                applicationEndDate,
                winnerAnnouncementDate,
                originalUrl,
                ReceptionPlace.create(sourceProvider, ReceptionMethod.ONLINE, null, contact, originalUrl),
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

    private String originalUrlOf(MyHomeAnnouncementSource source) {
        if (parser.normalizedText(source.getUrl()) != null) {
            return parser.normalizedText(source.getUrl());
        }
        if (parser.normalizedText(source.getPcUrl()) != null) {
            return parser.normalizedText(source.getPcUrl());
        }
        return parser.normalizedText(source.getMobileUrl());
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
