package com.toadzip.backend.housing.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

import com.toadzip.backend.housing.dto.response.CurrentAnnouncementResponse;
import com.toadzip.backend.housing.dto.response.CurrentSupplyConditionResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexAddressResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexDetailResponse;
import com.toadzip.backend.housing.dto.response.HousingTypeDetailResponse;
import com.toadzip.backend.housing.repository.ComplexDetailRow;
import com.toadzip.backend.housing.repository.CurrentAnnouncementRow;
import com.toadzip.backend.housing.repository.CurrentAnnouncementTargetRow;
import com.toadzip.backend.housing.repository.CurrentSupplyConditionRow;
import com.toadzip.backend.housing.repository.HousingTypeDetailRow;
import com.toadzip.backend.region.repository.RegionCodeResolver;

@Component
public class HousingComplexDetailMapper {

    private final HousingComplexCodeMapper codeMapper;

    private final RegionCodeResolver regionCodeResolver;

    public HousingComplexDetailMapper(
            HousingComplexCodeMapper codeMapper,
            RegionCodeResolver regionCodeResolver
    ) {
        this.codeMapper = codeMapper;
        this.regionCodeResolver = regionCodeResolver;
    }

    public HousingComplexDetailResponse toResponse(
            ComplexDetailRow complex,
            List<HousingTypeDetailRow> housingTypes,
            List<CurrentSupplyConditionRow> supplyConditions,
            List<CurrentAnnouncementRow> announcements,
            List<CurrentAnnouncementTargetRow> announcementTargets,
            LocalDate today
    ) {
        Map<Long, List<CurrentSupplyConditionResponse>> conditionsByHousingType = groupSupplyConditions(
                supplyConditions
        );
        Map<Long, List<String>> targetsByAnnouncement = groupAnnouncementTargets(announcementTargets);
        return new HousingComplexDetailResponse(
                complex.complexId(),
                complex.name(),
                codeMapper.toRentalType(complex.rentalType()),
                codeMapper.toAgency(complex.agencyCode()),
                toAddress(complex),
                complex.completionDate(),
                codeMapper.toBuildingType(complex.buildingType()),
                complex.hasElevator(),
                codeMapper.toHeatingType(complex.heatingType()),
                codeMapper.toCorridorType(complex.corridorType()),
                complex.moveOutCountLastYear(),
                complex.totalHouseholdCount(),
                complex.totalParkingCount(),
                toImages(complex.imageUrl()),
                null,
                toHousingTypes(housingTypes, conditionsByHousingType),
                toAnnouncements(announcements, targetsByAnnouncement, today)
        );
    }

    private Map<Long, List<CurrentSupplyConditionResponse>> groupSupplyConditions(
            List<CurrentSupplyConditionRow> rows
    ) {
        Map<Long, List<CurrentSupplyConditionResponse>> grouped = new LinkedHashMap<>();
        for (CurrentSupplyConditionRow row : rows) {
            grouped.computeIfAbsent(row.housingTypeId(), ignored -> new ArrayList<>())
                    .add(toSupplyCondition(row));
        }
        return grouped;
    }

    private CurrentSupplyConditionResponse toSupplyCondition(CurrentSupplyConditionRow row) {
        return new CurrentSupplyConditionResponse(
                row.target(),
                toLongExact(row.deposit()),
                toLongExact(row.monthlyRent()),
                toLongExact(row.convertibleDeposit())
        );
    }

    private Map<Long, List<String>> groupAnnouncementTargets(List<CurrentAnnouncementTargetRow> rows) {
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        for (CurrentAnnouncementTargetRow row : rows) {
            grouped.computeIfAbsent(row.announcementId(), ignored -> new ArrayList<>())
                    .add(row.target());
        }
        return grouped;
    }

    private HousingComplexAddressResponse toAddress(ComplexDetailRow complex) {
        String regionName = regionCodeResolver.resolve(
                complex.provinceCode(),
                complex.cityCountyDistrictCode()
        ).orElseThrow(() -> new IllegalStateException("저장된 단지 지역코드를 해석할 수 없다."));
        return new HousingComplexAddressResponse(
                regionName,
                complex.roadAddress(),
                complex.latitude(),
                complex.longitude()
        );
    }

    private List<String> toImages(String imageUrl) {
        if (imageUrl == null) {
            return List.of();
        }
        return List.of(imageUrl);
    }

    private List<HousingTypeDetailResponse> toHousingTypes(
            List<HousingTypeDetailRow> rows,
            Map<Long, List<CurrentSupplyConditionResponse>> conditionsByHousingType
    ) {
        return rows.stream()
                .map(row -> toHousingType(row, conditionsByHousingType))
                .toList();
    }

    private HousingTypeDetailResponse toHousingType(
            HousingTypeDetailRow row,
            Map<Long, List<CurrentSupplyConditionResponse>> conditionsByHousingType
    ) {
        List<CurrentSupplyConditionResponse> conditions = conditionsByHousingType.getOrDefault(
                row.housingTypeId(),
                List.of()
        );
        return new HousingTypeDetailResponse(
                row.housingTypeId(),
                row.name(),
                row.exclusiveArea(),
                row.supplyArea(),
                row.floorPlanImageUrl(),
                null,
                row.isDuplex(),
                toLongExact(row.maintenanceFee()),
                conditions
        );
    }

    private List<CurrentAnnouncementResponse> toAnnouncements(
            List<CurrentAnnouncementRow> rows,
            Map<Long, List<String>> targetsByAnnouncement,
            LocalDate today
    ) {
        return rows.stream()
                .map(row -> toAnnouncement(row, targetsByAnnouncement, today))
                .toList();
    }

    private CurrentAnnouncementResponse toAnnouncement(
            CurrentAnnouncementRow row,
            Map<Long, List<String>> targetsByAnnouncement,
            LocalDate today
    ) {
        return new CurrentAnnouncementResponse(
                row.announcementId(),
                row.title(),
                codeMapper.toPublicationType(row.publicationType()),
                applicationStatus(row, today),
                targetsByAnnouncement.getOrDefault(row.announcementId(), List.of()),
                row.applicationStartAt(),
                row.applicationEndAt(),
                Math.toIntExact(ChronoUnit.DAYS.between(today, row.applicationEndAt())),
                null
        );
    }

    private String applicationStatus(CurrentAnnouncementRow row, LocalDate today) {
        if (today.isBefore(row.applicationStartAt())) {
            return "BEFORE_APPLICATION";
        }
        return "APPLYING";
    }

    private Long toLongExact(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.longValueExact();
    }
}
