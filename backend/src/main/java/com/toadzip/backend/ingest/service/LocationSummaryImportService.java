package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.LocationSummaryRecord;
import com.toadzip.backend.ingest.domain.NormalizedRoadAddress;
import com.toadzip.backend.ingest.dto.LocationSummaryImportReport;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.repository.LocationSummaryStore;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingCandidateStore;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingExecutionLock;
import com.toadzip.backend.ingest.repository.MyHomeComplexSourceRepository;
import com.toadzip.backend.ingest.repository.external.LocationSummaryFileParseResult;
import com.toadzip.backend.ingest.repository.external.LocationSummaryFileParser;
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

    private final MyHomeComplexSourceRepository sourceRepository;

    private final MyHomeComplexMappingCandidateStore candidateStore;

    private final MyHomeComplexMappingExecutionLock executionLock;

    private final TransactionTemplate transactionTemplate;

    public LocationSummaryImportService(
            LocationSummaryFileParser parser,
            LocationSummaryStore locationStore,
            MyHomeComplexSourceRepository sourceRepository,
            MyHomeComplexMappingCandidateStore candidateStore,
            MyHomeComplexMappingExecutionLock executionLock,
            TransactionTemplate transactionTemplate
    ) {
        this.parser = parser;
        this.locationStore = locationStore;
        this.sourceRepository = sourceRepository;
        this.candidateStore = candidateStore;
        this.executionLock = executionLock;
        this.transactionTemplate = transactionTemplate;
    }

    public LocationSummaryImportReport importMatches(String sourceFileName, InputStream input) {
        return executionLock.tryRun(() -> importUnlocked(sourceFileName, input))
                .orElseThrow(() -> new IngestAlreadyRunningException(
                "단지 매핑 또는 위치정보요약DB 선별 적재가 이미 실행 중입니다."
        ));
    }

    private LocationSummaryImportReport importUnlocked(String sourceFileName, InputStream input) {
        Set<String> targetAddresses = normalizedTargetAddresses();
        if (targetAddresses.isEmpty()) {
            throw new InvalidIngestRequestException(
                    "좌표를 찾을 단지 도로명주소가 없습니다. 마이홈 단지 원천 데이터를 먼저 수집해야 합니다."
            );
        }

        Set<String> matchedAddresses = new HashSet<>();
        List<LocationSummaryRecord> selectedRecords = new ArrayList<>();
        LocationSummaryFileParseResult parseResult = parser.parse(input, record -> selectIfMatched(
                record,
                targetAddresses,
                matchedAddresses,
                selectedRecords
        ));
        validateNationwide(parseResult);
        validateMatchedAddresses(matchedAddresses);

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
                replacement.storedLocationCount(),
                replacement.replacedRowCount(),
                replacement.invalidatedCandidateCount(),
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
                // 비어 있는 원천 주소는 단지 매핑 준비 단계에서 별도 실패로 기록한다.
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
        long replacedRowCount = locationStore.count();
        locationStore.truncate();
        long storedLocationCount;
        try (LocationSummaryStore.CopyWriter writer = locationStore.openWriter()) {
            selectedRecords.forEach(writer::write);
            storedLocationCount = writer.complete();
        }
        long invalidatedCandidateCount = candidateStore.invalidateAll();
        return new ReplacementResult(
                storedLocationCount,
                replacedRowCount,
                invalidatedCandidateCount
        );
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

    private void validateMatchedAddresses(Set<String> matchedAddresses) {
        if (!matchedAddresses.isEmpty()) {
            return;
        }
        throw new InvalidIngestRequestException(
                "위치정보요약DB에서 단지 도로명주소와 일치하는 "
                        + "좌표 대상을 찾지 못했습니다."
        );
    }

    private String sourceFileName(String sourceFileName) {
        if (sourceFileName == null || sourceFileName.isBlank()) {
            return "location-summary.zip";
        }
        return sourceFileName.strip();
    }

    private record ReplacementResult(
            long storedLocationCount,
            long replacedRowCount,
            long invalidatedCandidateCount
    ) {
    }
}
