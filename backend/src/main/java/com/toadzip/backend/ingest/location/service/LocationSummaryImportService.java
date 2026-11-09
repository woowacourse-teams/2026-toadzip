package com.toadzip.backend.ingest.location.service;

import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.location.domain.LocationSummaryRecord;
import com.toadzip.backend.ingest.location.domain.NormalizedRoadAddress;
import com.toadzip.backend.ingest.location.domain.UtmKCoordinate;
import com.toadzip.backend.ingest.location.dto.LocationSummaryImportReport;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.repository.LocationSummaryStore;
import com.toadzip.backend.ingest.location.repository.external.LocationSummaryFileParseResult;
import com.toadzip.backend.ingest.location.repository.external.LocationSummaryFileParser;
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LocationSummaryImportService {

    private static final int MAX_SELECTED_ROW_COUNT = 500_000;

    private static final Map<String, Set<String>> NATIONWIDE_ENTRY_PROVINCE_CODES = Map.ofEntries(
            Map.entry("entrc_busan.txt", Set.of("26")),
            Map.entry("entrc_chungbuk.txt", Set.of("43")),
            Map.entry("entrc_chungnam.txt", Set.of("44")),
            Map.entry("entrc_daegu.txt", Set.of("27")),
            Map.entry("entrc_daejeon.txt", Set.of("30")),
            Map.entry("entrc_gangwon.txt", Set.of("51")),
            Map.entry("entrc_gyeongbuk.txt", Set.of("47")),
            Map.entry("entrc_gyeongnam.txt", Set.of("48")),
            Map.entry("entrc_gyunggi.txt", Set.of("41")),
            Map.entry("entrc_incheon.txt", Set.of("28")),
            Map.entry("entrc_jeju.txt", Set.of("50")),
            Map.entry("entrc_jeonbuk.txt", Set.of("52")),
            Map.entry("entrc_jeonnamgwangju.txt", Set.of("12")),
            Map.entry("entrc_sejong.txt", Set.of("36")),
            Map.entry("entrc_seoul.txt", Set.of("11")),
            Map.entry("entrc_ulsan.txt", Set.of("31"))
    );

    private final LocationSummaryFileParser parser;

    private final LocationSummaryStore locationStore;

    private final MyHomeComplexSourceReader sourceRepository;

    private final TransactionTemplate transactionTemplate;

    private final MyHomeInitialRegistrationService registration;

    private final UtmKCoordinateConverter converter;

    public LocationSummaryImportService(
            LocationSummaryFileParser parser,
            LocationSummaryStore locationStore,
            MyHomeComplexSourceReader sourceRepository,
            TransactionTemplate transactionTemplate,
            MyHomeInitialRegistrationService registration,
            UtmKCoordinateConverter converter
    ) {
        this.parser = parser;
        this.locationStore = locationStore;
        this.sourceRepository = sourceRepository;
        this.transactionTemplate = transactionTemplate;
        this.registration = registration;
        this.converter = converter;
    }

    public LocationSummaryImportReport importMatches(String sourceFileName, InputStream input) {
        IngestExecutionScope.requireHeld();
        Set<String> targetAddresses = normalizedTargetAddresses();

        Set<String> matchedAddresses = new HashSet<>();
        List<LocationSummaryRecord> selectedRecords = new ArrayList<>();
        LocationSummaryFileParseResult parseResult = parser.parse(input, record -> selectIfMatched(
                record,
                targetAddresses,
                matchedAddresses,
                selectedRecords
        ));
        validateNationwide(parseResult);

        ReplacementResult replacement = Objects.requireNonNull(transactionTemplate.execute(
                status -> replaceLocations(selectedRecords)
        ));

        List<String> provinceCodes = new ArrayList<>(parseResult.provinceCodes());
        provinceCodes.sort(String::compareTo);
        return new LocationSummaryImportReport(
                sourceFileName(sourceFileName),
                parseResult.entryCount(),
                parseResult.rowCount(),
                targetAddresses.size(),
                matchedAddresses.size(),
                targetAddresses.size() - matchedAddresses.size(),
                (int) selectedRecords.stream().filter(this::convertible)
                        .map(LocationSummaryRecord::normalizedRoadAddress).distinct().count(),
                replacement.storedLocationCount(),
                replacement.replacedRowCount(),
                0, // 기존 관리자 응답과의 호환용 필드: 매핑 후보 상태는 더 이상 사용하지 않는다.
                provinceCodes
        );
    }

    private Set<String> normalizedTargetAddresses() {
        Set<String> addresses = new HashSet<>();
        for (String roadAddress : sourceRepository.findDistinctRoadAddresses()) {
            try {
                addresses.add(new NormalizedRoadAddress(roadAddress).withoutReference());
            }
            catch (IllegalArgumentException ignored) {
                // 주소 누락은 등록 대상별 주소·좌표 준비 결과에서 집계한다.
            }
        }
        return addresses;
    }

    private void selectIfMatched(
            LocationSummaryRecord record,
            Set<String> targetAddresses,
            Set<String> matchedAddresses,
            List<LocationSummaryRecord> selectedRecords
    ) {
        String address = record.normalizedRoadAddress();
        if (!targetAddresses.contains(address)) {
            return;
        }
        matchedAddresses.add(address);
        selectedRecords.add(record);
        if (selectedRecords.size() > MAX_SELECTED_ROW_COUNT) {
            throw new InvalidIngestRequestException(
                    "단지 주소와 일치한 위치정보요약DB 행 수가 허용 범위를 초과했습니다."
            );
        }
    }

    private ReplacementResult replaceLocations(List<LocationSummaryRecord> selectedRecords) {
        long storedLocationCount;
        try (LocationSummaryStore.CopyWriter writer = locationStore.openWriter()) {
            selectedRecords.forEach(record -> writer.write(record, convertible(record)));
            storedLocationCount = writer.complete();
        }
        long replacedRowCount = locationStore.mergeUploadedLocations();
        registration.refresh();
        return new ReplacementResult(storedLocationCount, replacedRowCount);
    }

    private boolean convertible(LocationSummaryRecord record) {
        if (record.x() == null || record.y() == null) {
            return false;
        }
        try {
            converter.convert(new UtmKCoordinate(record.x(), record.y()));
            return true;
        }
        catch (RoadAddressGeocodingException exception) {
            return false;
        }
    }

    private void validateNationwide(LocationSummaryFileParseResult parseResult) {
        Map<String, Set<String>> actualEntries = parseResult.provinceCodesByEntry();
        if (NATIONWIDE_ENTRY_PROVINCE_CODES.equals(actualEntries)) {
            return;
        }
        throw new InvalidIngestRequestException(
                "전국 월전체분이 아닙니다. 누락 파일=" + missingEntries(actualEntries)
                        + ", 알 수 없는 파일=" + unknownEntries(actualEntries)
                        + ", 비어 있거나 시도코드가 다른 파일=" + invalidEntries(actualEntries)
        );
    }

    private Set<String> missingEntries(Map<String, Set<String>> actualEntries) {
        Set<String> missing = new HashSet<>(NATIONWIDE_ENTRY_PROVINCE_CODES.keySet());
        missing.removeAll(actualEntries.keySet());
        return missing;
    }

    private Set<String> unknownEntries(Map<String, Set<String>> actualEntries) {
        Set<String> unknown = new HashSet<>(actualEntries.keySet());
        unknown.removeAll(NATIONWIDE_ENTRY_PROVINCE_CODES.keySet());
        return unknown;
    }

    private Set<String> invalidEntries(Map<String, Set<String>> actualEntries) {
        Set<String> invalid = new HashSet<>(NATIONWIDE_ENTRY_PROVINCE_CODES.keySet());
        invalid.retainAll(actualEntries.keySet());
        invalid.removeIf(entryName -> NATIONWIDE_ENTRY_PROVINCE_CODES.get(entryName)
                .equals(actualEntries.get(entryName)));
        return invalid;
    }

    private String sourceFileName(String sourceFileName) {
        if (sourceFileName == null || sourceFileName.isBlank()) {
            return "location-summary.zip";
        }
        return sourceFileName.strip();
    }

    private record ReplacementResult(
            long storedLocationCount,
            long replacedRowCount
    ) {
    }
}
