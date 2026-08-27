package com.toadzip.backend.housing.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.toadzip.backend.housing.dto.response.HousingComplexListItemResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexMapItemResponse;
import com.toadzip.backend.housing.dto.response.RepresentativeAnnouncementResponse;
import com.toadzip.backend.housing.repository.ComplexSummaryRow;
import com.toadzip.backend.region.repository.RegionCodeResolver;

@Component
public class HousingComplexSummaryMapper {

    private final HousingComplexCodeMapper codeMapper;

    private final RegionCodeResolver regionCodeResolver;

    @Autowired
    public HousingComplexSummaryMapper(
            HousingComplexCodeMapper codeMapper,
            RegionCodeResolver regionCodeResolver
    ) {
        this.codeMapper = codeMapper;
        this.regionCodeResolver = regionCodeResolver;
    }

    HousingComplexSummaryMapper(HousingComplexCodeMapper codeMapper) {
        this(codeMapper, (provinceCode, cityCountyDistrictCode) -> java.util.Optional.empty());
    }

    public HousingComplexMapItemResponse toMapItem(ComplexSummaryRow row) {
        return new HousingComplexMapItemResponse(
                row.complexId(),
                row.name(),
                row.latitude(),
                row.longitude(),
                codeMapper.toRentalType(row.rentalType()),
                codeMapper.toAgency(row.agencyCode()),
                row.exclusiveAreaMin(),
                row.exclusiveAreaMax(),
                toLongExact(row.depositMin()),
                toLongExact(row.depositMax()),
                toLongExact(row.monthlyRentMin()),
                toLongExact(row.monthlyRentMax())
        );
    }

    public List<HousingComplexListItemResponse> toListItems(List<ComplexSummaryRow> rows, LocalDate today) {
        return rows.stream().map(row -> toListItem(row, today)).toList();
    }

    private HousingComplexListItemResponse toListItem(ComplexSummaryRow row, LocalDate today) {
        return new HousingComplexListItemResponse(
                row.complexId(),
                row.imageUrl(),
                resolveRegionName(row),
                row.name(),
                codeMapper.toRentalType(row.rentalType()),
                codeMapper.toAgency(row.agencyCode()),
                row.exclusiveAreaMin(),
                row.exclusiveAreaMax(),
                toLongExact(row.depositMin()),
                toLongExact(row.depositMax()),
                toLongExact(row.monthlyRentMin()),
                toLongExact(row.monthlyRentMax()),
                toRepresentativeAnnouncement(row, today)
        );
    }

    private String resolveRegionName(ComplexSummaryRow row) {
        return regionCodeResolver.resolve(row.provinceCode(), row.cityCountyDistrictCode())
                .orElseThrow(() -> new IllegalStateException("저장된 단지 지역코드를 해석할 수 없다."));
    }

    private RepresentativeAnnouncementResponse toRepresentativeAnnouncement(
            ComplexSummaryRow row,
            LocalDate today
    ) {
        if (row.announcementId() == null) {
            return null;
        }
        String applicationStatus = applicationStatus(row, today);
        return new RepresentativeAnnouncementResponse(
                row.announcementId(),
                codeMapper.toPublicationType(row.publicationType()),
                applicationStatus,
                row.applicationEndDate(),
                dDay(row.applicationEndDate(), today, applicationStatus)
        );
    }

    private String applicationStatus(ComplexSummaryRow row, LocalDate today) {
        if (today.isBefore(row.applicationStartDate())) {
            return "BEFORE_APPLICATION";
        }
        if (today.isAfter(row.applicationEndDate())) {
            return "CLOSED";
        }
        return "APPLYING";
    }

    private Integer dDay(LocalDate applicationEndDate, LocalDate today, String applicationStatus) {
        if ("CLOSED".equals(applicationStatus)) {
            return null;
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(today, applicationEndDate));
    }

    private Long toLongExact(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.longValueExact();
    }
}
