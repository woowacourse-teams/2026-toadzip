package com.toadzip.backend.ingest.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementMappingReport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MyHomeAnnouncementMappingWriter {

    private final AnnouncementRepository announcementRepository;

    private final SupplyRowRepository supplyRowRepository;

    private final HousingComplexRepository housingComplexRepository;

    private final HousingTypeRepository housingTypeRepository;

    public MyHomeAnnouncementMappingWriter(
            AnnouncementRepository announcementRepository,
            SupplyRowRepository supplyRowRepository,
            HousingComplexRepository housingComplexRepository,
            HousingTypeRepository housingTypeRepository
    ) {
        this.announcementRepository = announcementRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.housingComplexRepository = housingComplexRepository;
        this.housingTypeRepository = housingTypeRepository;
    }

    @Transactional
    public MyHomeAnnouncementWriteResult write(
            MyHomeAnnouncementMappingData data,
            Announcement previousAnnouncement
    ) {
        AnnouncementWriteResult announcementResult = writeAnnouncement(data, previousAnnouncement);
        SupplyRowsWriteResult supplyRowsResult = writeSupplyRows(announcementResult.announcement(), data.supplyRows());
        return new MyHomeAnnouncementWriteResult(
                reportOf(announcementResult, supplyRowsResult),
                supplyRowsResult.failures()
        );
    }

    private AnnouncementWriteResult writeAnnouncement(
            MyHomeAnnouncementMappingData data,
            Announcement previousAnnouncement
    ) {
        Announcement stored = announcementRepository
                .findBySourceAnnouncementIdentifier(data.sourceAnnouncementIdentifier())
                .orElse(null);
        if (stored == null) {
            Announcement created = announcementRepository.save(Announcement.create(
                    data.sourceAnnouncementIdentifier(),
                    data.previousSourceAnnouncementIdentifier(),
                    previousAnnouncement,
                    data.name(),
                    data.publicationType(),
                    data.rentalType(),
                    data.recruitmentType(),
                    data.provider(),
                    data.postedDate(),
                    data.applicationStartDate(),
                    data.applicationEndDate(),
                    data.winnerAnnouncementDate(),
                    data.originalUrl(),
                    null,
                    0L,
                    data.receptionPlace()
            ));
            return new AnnouncementWriteResult(created, true, false);
        }
        boolean updated = stored.updateFromSource(
                data.previousSourceAnnouncementIdentifier(),
                previousAnnouncement,
                data.name(),
                data.publicationType(),
                data.rentalType(),
                data.recruitmentType(),
                data.provider(),
                data.postedDate(),
                data.applicationStartDate(),
                data.applicationEndDate(),
                data.winnerAnnouncementDate(),
                data.originalUrl(),
                null,
                data.receptionPlace()
        );
        return new AnnouncementWriteResult(stored, false, updated);
    }

    private SupplyRowsWriteResult writeSupplyRows(
            Announcement announcement,
            List<MyHomeSupplyRowMappingData> rows
    ) {
        Map<String, SupplyRow> storedRows = supplyRowRepository.findAllByAnnouncement(announcement)
                .stream()
                .collect(Collectors.toMap(
                        SupplyRow::getSourceSupplyRowIdentifier,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<MyHomeSupplyMatchingFailureData> failures = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            MyHomeSupplyRowMappingData data = rows.get(index);
            SupplyMatchResult match = match(data);
            if (match.failure() != null) {
                failures.add(match.failure());
            }
            SupplyRow stored = storedRows.remove(data.sourceSupplyRowIdentifier());
            if (stored == null) {
                supplyRowRepository.save(createSupplyRow(announcement, data, match, index + 1));
                created++;
                continue;
            }
            boolean changed = stored.updateFromSource(
                    match.complex(),
                    match.housingType(),
                    index + 1,
                    data.sourceComplexName(),
                    data.sourceHousingTypeName(),
                    data.pnu(),
                    null,
                    data.supplyCategory(),
                    match.failureDetail(),
                    data.totalSupplyHouseholdCount()
            );
            if (changed) {
                updated++;
                continue;
            }
            unchanged++;
        }
        return new SupplyRowsWriteResult(created, updated, unchanged, failures);
    }

    private SupplyRow createSupplyRow(
            Announcement announcement,
            MyHomeSupplyRowMappingData data,
            SupplyMatchResult match,
            int displayOrder
    ) {
        return SupplyRow.create(
                announcement,
                match.complex(),
                match.housingType(),
                data.sourceSupplyRowIdentifier(),
                displayOrder,
                data.sourceComplexName(),
                data.sourceHousingTypeName(),
                data.pnu(),
                null,
                data.supplyCategory(),
                match.failureDetail(),
                data.totalSupplyHouseholdCount()
        );
    }

    private SupplyMatchResult match(MyHomeSupplyRowMappingData data) {
        List<HousingComplex> complexes = housingComplexRepository.findAllByPnuAndSupplyType(
                data.pnu(),
                data.complexSupplyType()
        );
        if (complexes.isEmpty()) {
            return SupplyMatchResult.failure(
                    data,
                    MyHomeAnnouncementMappingFailureReason.COMPLEX_NOT_FOUND,
                    "PNU와 공급유형이 일치하는 단지가 없습니다."
            );
        }
        if (complexes.size() > 1) {
            HousingComplex matched = uniqueComplexByName(complexes, data.sourceComplexName());
            if (matched == null) {
                return SupplyMatchResult.failure(
                        data,
                        MyHomeAnnouncementMappingFailureReason.AMBIGUOUS_COMPLEX,
                        "PNU와 공급유형이 일치하는 단지를 단지명으로도 하나로 확정할 수 없습니다."
                );
            }
            return matchHousingType(data, matched);
        }
        return matchHousingType(data, complexes.getFirst());
    }

    private HousingComplex uniqueComplexByName(List<HousingComplex> complexes, String sourceName) {
        String normalizedSourceName = MyHomeSupplyNameNormalizer.complexName(sourceName);
        List<HousingComplex> matched = complexes.stream()
                .filter(complex -> MyHomeSupplyNameNormalizer.complexName(complex.getName())
                        .equals(normalizedSourceName))
                .toList();
        if (matched.size() != 1) {
            return null;
        }
        return matched.getFirst();
    }

    private SupplyMatchResult matchHousingType(MyHomeSupplyRowMappingData data, HousingComplex complex) {
        List<HousingType> housingTypes = housingTypeRepository.findAllByHousingComplex(complex);
        if (housingTypes.isEmpty()) {
            return SupplyMatchResult.failure(
                    data,
                    complex,
                    MyHomeAnnouncementMappingFailureReason.HOUSING_TYPE_NOT_FOUND,
                    "단지에 연결된 주택형이 없습니다."
            );
        }
        if (housingTypes.size() > 1) {
            HousingType matched = uniqueHousingTypeByName(housingTypes, data.sourceHousingTypeName());
            if (matched == null) {
                matched = uniqueHousingTypeByExclusiveArea(housingTypes, data.exclusiveArea());
            }
            if (matched == null) {
                matched = uniqueHousingTypeBySupplyArea(housingTypes, data.supplyArea());
            }
            if (matched == null) {
                return SupplyMatchResult.failure(
                        data,
                        complex,
                        MyHomeAnnouncementMappingFailureReason.AMBIGUOUS_HOUSING_TYPE,
                        "LH 공급행의 주택형명과 면적으로도 주택형 하나를 확정할 수 없습니다."
                );
            }
            return SupplyMatchResult.matched(complex, matched);
        }
        return SupplyMatchResult.matched(complex, housingTypes.getFirst());
    }

    private HousingType uniqueHousingTypeByName(List<HousingType> housingTypes, String sourceName) {
        String normalizedSourceName = MyHomeSupplyNameNormalizer.housingTypeName(sourceName);
        List<HousingType> matched = housingTypes.stream()
                .filter(housingType -> MyHomeSupplyNameNormalizer.housingTypeName(housingType.getName())
                        .equals(normalizedSourceName))
                .toList();
        if (matched.size() != 1) {
            return null;
        }
        return matched.getFirst();
    }

    private HousingType uniqueHousingTypeByExclusiveArea(
            List<HousingType> housingTypes,
            BigDecimal exclusiveArea
    ) {
        if (exclusiveArea == null) {
            return null;
        }
        return uniqueHousingTypeByArea(
                housingTypes,
                housingType -> housingType.getExclusiveArea(),
                exclusiveArea
        );
    }

    private HousingType uniqueHousingTypeBySupplyArea(
            List<HousingType> housingTypes,
            BigDecimal supplyArea
    ) {
        if (supplyArea == null) {
            return null;
        }
        return uniqueHousingTypeByArea(
                housingTypes,
                HousingType::getSupplyArea,
                supplyArea
        );
    }

    private HousingType uniqueHousingTypeByArea(
            List<HousingType> housingTypes,
            Function<HousingType, BigDecimal> areaExtractor,
            BigDecimal sourceArea
    ) {
        List<HousingType> matched = housingTypes.stream()
                .filter(housingType -> sameArea(areaExtractor.apply(housingType), sourceArea))
                .toList();
        if (matched.size() != 1) {
            return null;
        }
        return matched.getFirst();
    }

    private boolean sameArea(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private MyHomeAnnouncementMappingReport reportOf(
            AnnouncementWriteResult announcement,
            SupplyRowsWriteResult supplyRows
    ) {
        int createdAnnouncement = announcement.created() ? 1 : 0;
        int updatedAnnouncement = announcement.updated() ? 1 : 0;
        int unchangedAnnouncement = announcement.unchanged() ? 1 : 0;
        return new MyHomeAnnouncementMappingReport(
                createdAnnouncement,
                updatedAnnouncement,
                unchangedAnnouncement,
                supplyRows.created(),
                supplyRows.updated(),
                supplyRows.unchanged(),
                supplyRows.failures().size()
        );
    }

    private record AnnouncementWriteResult(Announcement announcement, boolean created, boolean updated) {

        boolean unchanged() {
            return !created && !updated;
        }
    }

    private record SupplyRowsWriteResult(
            int created,
            int updated,
            int unchanged,
            List<MyHomeSupplyMatchingFailureData> failures
    ) {
    }

    private record SupplyMatchResult(
            HousingComplex complex,
            HousingType housingType,
            MyHomeSupplyMatchingFailureData failure
    ) {

        static SupplyMatchResult matched(HousingComplex complex, HousingType housingType) {
            return new SupplyMatchResult(complex, housingType, null);
        }

        static SupplyMatchResult failure(
                MyHomeSupplyRowMappingData data,
                MyHomeAnnouncementMappingFailureReason reason,
                String detail
        ) {
            return failure(data, null, reason, detail);
        }

        static SupplyMatchResult failure(
                MyHomeSupplyRowMappingData data,
                HousingComplex complex,
                MyHomeAnnouncementMappingFailureReason reason,
                String detail
        ) {
            return new SupplyMatchResult(
                    complex,
                    null,
                    new MyHomeSupplyMatchingFailureData(data.source(), reason, detail)
            );
        }

        String failureDetail() {
            if (failure == null) {
                return null;
            }
            return failure.detail();
        }
    }
}

record MyHomeAnnouncementWriteResult(
        MyHomeAnnouncementMappingReport report,
        List<MyHomeSupplyMatchingFailureData> failures
) {
}

record MyHomeSupplyMatchingFailureData(
        MyHomeAnnouncementSource source,
        MyHomeAnnouncementMappingFailureReason reason,
        String detail
) {
}
