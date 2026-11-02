package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MyHomeAnnouncementCommonValuesMapper {

    private final MyHomeAnnouncementValueParser parser = new MyHomeAnnouncementValueParser();
    private final MyHomeAnnouncementClassificationPolicy classification =
            new MyHomeAnnouncementClassificationPolicy(parser);

    MyHomeAnnouncementCommonValues map(List<MyHomeAnnouncementSourceView> sources) {
        sources = MyHomeAnnouncementCurrentSources.select(sources);
        String identifier = parser.requiredText(sources, MyHomeAnnouncementSourceView::getPblancId, "공고 식별자");
        String name = parser.requiredText(sources, MyHomeAnnouncementSourceView::getPblancNm, "공고명");
        String sourceStatus = parser.requiredText(sources, MyHomeAnnouncementSourceView::getSttusNm, "공고 상태");
        String sourceSupplyType = parser.requiredText(
                sources,
                MyHomeAnnouncementSourceView::getSuplyTyNm,
                "공급유형"
        );
        String sourceProvider = parser.requiredText(
                sources,
                MyHomeAnnouncementSourceView::getSuplyInsttNm,
                "공급기관"
        );
        String previousIdentifier = parser.optionalText(
                sources,
                MyHomeAnnouncementSourceView::getBeforePblancId,
                "이전 공고 식별자"
        );
        String originalUrl = parser.requiredText(sources, this::originalUrlOf, "원문 URL");
        String contact = parser.optionalText(sources, MyHomeAnnouncementSourceView::getRefrnc, "문의처");
        AnnouncementPublicationType publicationType = classification.publicationType(sourceStatus, previousIdentifier);
        RentalType rentalType = classification.rentalType(sourceSupplyType);
        LocalDate postedDate = parser.commonDate(
                sources,
                MyHomeAnnouncementSourceView::getRcritPblancDe,
                "모집 공고일"
        );
        LocalDate applicationStartDate = parser.commonDate(
                sources,
                MyHomeAnnouncementSourceView::getBeginDe,
                "모집 시작일"
        );
        LocalDate applicationEndDate = parser.commonDate(
                sources,
                MyHomeAnnouncementSourceView::getEndDe,
                "모집 종료일"
        );
        LocalDate winnerAnnouncementDate = parser.commonDate(
                sources,
                MyHomeAnnouncementSourceView::getPrzwnerPresnatnDe,
                "당첨자 발표일"
        );
        parser.validateApplicationPeriod(applicationStartDate, applicationEndDate);
        return new MyHomeAnnouncementCommonValues(
                identifier, name, sourceSupplyType, sourceProvider, previousIdentifier, originalUrl, contact,
                publicationType, rentalType, postedDate, applicationStartDate, applicationEndDate,
                winnerAnnouncementDate
        );
    }

    private String originalUrlOf(MyHomeAnnouncementSourceView source) {
        if (parser.normalizedText(source.getUrl()) != null) {
            return parser.normalizedText(source.getUrl());
        }
        if (parser.normalizedText(source.getPcUrl()) != null) {
            return parser.normalizedText(source.getPcUrl());
        }
        return parser.normalizedText(source.getMobileUrl());
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
}
