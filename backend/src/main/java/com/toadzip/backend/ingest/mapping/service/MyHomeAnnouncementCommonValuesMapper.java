package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MyHomeAnnouncementCommonValuesMapper {

    private final MyHomeAnnouncementValueParser parser = new MyHomeAnnouncementValueParser();
    private final MyHomeAnnouncementClassificationPolicy classification =
            new MyHomeAnnouncementClassificationPolicy(parser);

    MyHomeAnnouncementCommonValues map(List<MyHomeAnnouncementSource> sources) {
        sources = MyHomeAnnouncementCurrentSources.select(sources);
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
        return new MyHomeAnnouncementCommonValues(
                identifier, name, sourceSupplyType, sourceProvider, previousIdentifier, originalUrl, contact,
                publicationType, rentalType, postedDate, applicationStartDate, applicationEndDate,
                winnerAnnouncementDate
        );
    }

    public String rejectionDetail(List<MyHomeAnnouncementSource> sources) {
        try {
            map(sources);
            return null;
        }
        catch (MyHomeAnnouncementMappingRejectedException exception) {
            return exception.getMessage();
        }
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

record MyHomeAnnouncementCommonValues(
        String identifier,
        String name,
        String sourceSupplyType,
        String sourceProvider,
        String previousIdentifier,
        String originalUrl,
        String contact,
        AnnouncementPublicationType publicationType,
        RentalType rentalType,
        LocalDate postedDate,
        LocalDate applicationStartDate,
        LocalDate applicationEndDate,
        LocalDate winnerAnnouncementDate
) {
}
