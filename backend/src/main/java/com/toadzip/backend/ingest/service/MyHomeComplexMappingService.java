package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingFailureResponse;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingFailureStore;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingFailureRepository;
import com.toadzip.backend.ingest.repository.MyHomeComplexSourceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class MyHomeComplexMappingService {

    private static final String PERSISTENCE_FAILURE_DETAIL = "단지와 주택형 저장 중 오류가 발생했습니다.";

    private final MyHomeComplexSourceRepository sourceRepository;

    private final MyHomeComplexMappingFailureRepository failureRepository;

    private final MyHomeComplexMappingFailureStore failureStore;

    private final MyHomeComplexSourceMapper sourceMapper;

    private final MyHomeComplexMappingWriter writer;

    private final Clock clock;

    public MyHomeComplexMappingService(
            MyHomeComplexSourceRepository sourceRepository,
            MyHomeComplexMappingFailureRepository failureRepository,
            MyHomeComplexMappingFailureStore failureStore,
            MyHomeComplexSourceMapper sourceMapper,
            MyHomeComplexMappingWriter writer,
            Clock clock
    ) {
        this.sourceRepository = sourceRepository;
        this.failureRepository = failureRepository;
        this.failureStore = failureStore;
        this.sourceMapper = sourceMapper;
        this.writer = writer;
        this.clock = clock;
    }

    public MyHomeComplexMappingReport mapAll() {
        Instant occurredAt = clock.instant();
        List<MyHomeComplexMappingFailure> failures = new ArrayList<>();
        Map<String, List<MyHomeComplexSource>> groupedSources = groupSources(failures, occurredAt);
        MyHomeComplexMappingReport report = MyHomeComplexMappingReport.failedRows(failures.size());
        for (Map.Entry<String, List<MyHomeComplexSource>> entry : groupedSources.entrySet()) {
            report = report.plus(mapGroup(entry.getKey(), entry.getValue(), failures, occurredAt));
        }
        failureStore.replaceAll(failures);
        return report;
    }

    @Transactional(readOnly = true)
    public List<MyHomeComplexMappingFailureResponse> findFailures() {
        return failureRepository.findAllByOrderBySourceKeyAsc()
                .stream()
                .map(MyHomeComplexMappingFailureResponse::from)
                .toList();
    }

    private Map<String, List<MyHomeComplexSource>> groupSources(
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        Map<String, List<MyHomeComplexSource>> grouped = new LinkedHashMap<>();
        for (MyHomeComplexSource source : sourceRepository.findAll()) {
            if (source.getHsmpSn() == null) {
                failures.add(failureOf(
                        source,
                        null,
                        MyHomeComplexMappingFailureReason.MISSING_REQUIRED_VALUE,
                        "단지 식별자 값이 없습니다.",
                        occurredAt
                ));
                continue;
            }
            String sourceComplexIdentifier = String.valueOf(source.getHsmpSn());
            grouped.computeIfAbsent(sourceComplexIdentifier, ignored -> new ArrayList<>()).add(source);
        }
        return grouped;
    }

    private MyHomeComplexMappingReport mapGroup(
            String sourceComplexIdentifier,
            List<MyHomeComplexSource> sources,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        try {
            MyHomeComplexMappingData data = sourceMapper.map(sourceComplexIdentifier, sources);
            return writer.write(data);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            addFailures(
                    failures,
                    sources,
                    sourceComplexIdentifier,
                    exception.reason(),
                    exception.getMessage(),
                    occurredAt
            );
            return MyHomeComplexMappingReport.failedRows(sources.size());
        }
        catch (RuntimeException exception) {
            log.warn(
                    "마이홈 단지 매핑 저장에 실패했습니다: sourceComplexIdentifier={}, sourceRowCount={}",
                    sourceComplexIdentifier,
                    sources.size(),
                    exception
            );
            addFailures(
                    failures,
                    sources,
                    sourceComplexIdentifier,
                    MyHomeComplexMappingFailureReason.PERSISTENCE_ERROR,
                    PERSISTENCE_FAILURE_DETAIL,
                    occurredAt
            );
            return MyHomeComplexMappingReport.failedRows(sources.size());
        }
    }

    private void addFailures(
            List<MyHomeComplexMappingFailure> failures,
            List<MyHomeComplexSource> sources,
            String sourceComplexIdentifier,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        for (MyHomeComplexSource source : sources) {
            failures.add(failureOf(source, sourceComplexIdentifier, reason, detail, occurredAt));
        }
    }

    private MyHomeComplexMappingFailure failureOf(
            MyHomeComplexSource source,
            String sourceComplexIdentifier,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        return MyHomeComplexMappingFailure.create(
                source.getSourceKey(),
                sourceComplexIdentifier,
                reason,
                detail,
                occurredAt
        );
    }
}
