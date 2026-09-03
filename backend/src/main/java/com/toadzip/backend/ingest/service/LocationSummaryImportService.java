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
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LocationSummaryImportService {

    private static final int MAX_SELECTED_ROW_COUNT = 500_000;

    private static final Set<String> NATIONWIDE_ENTRY_NAMES = Set.of(
            "entrc_busan.txt",
            "entrc_chungbuk.txt",
            "entrc_chungnam.txt",
            "entrc_daegu.txt",
            "entrc_daejeon.txt",
            "entrc_gangwon.txt",
            "entrc_gyeongbuk.txt",
            "entrc_gyeongnam.txt",
            "entrc_gyunggi.txt",
            "entrc_incheon.txt",
            "entrc_jeju.txt",
            "entrc_jeonbuk.txt",
            "entrc_jeonnamgwangju.txt",
            "entrc_sejong.txt",
            "entrc_seoul.txt",
            "entrc_ulsan.txt"
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
        validateNationwide(parseResult.entryNames());

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

    private void validateNationwide(Set<String> actualEntryNames) {
        if (NATIONWIDE_ENTRY_NAMES.equals(actualEntryNames)) {
            return;
        }
        Set<String> missing = new HashSet<>(NATIONWIDE_ENTRY_NAMES);
        missing.removeAll(actualEntryNames);
        Set<String> unknown = new HashSet<>(actualEntryNames);
        unknown.removeAll(NATIONWIDE_ENTRY_NAMES);
        throw new InvalidIngestRequestException(
                "전국 월전체분이 아닙니다. 누락 파일=" + missing + ", 알 수 없는 파일=" + unknown
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
