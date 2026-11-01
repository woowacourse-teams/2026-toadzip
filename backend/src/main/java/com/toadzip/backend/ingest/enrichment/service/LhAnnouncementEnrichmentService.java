package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.ingest.collection.lh.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentFailureResponse;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureStore;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** LH가 공급하는 마이홈 공고를 모두 보강하고, 이번 실행의 실패로 실패 검토 목록을 맞춘다. 그 실패 목록도 조회한다. */
@Service
@RequiredArgsConstructor
public class LhAnnouncementEnrichmentService {

    private final MyHomeAnnouncementSourceReader myHomeSourceRepository;
    private final LhAnnouncementEnrichmentFailureStore failureStore;
    private final LhEnrichmentMappingState mappingState;
    private final LhAnnouncementEnricher enricher;
    private final Clock clock;

    public LhAnnouncementEnrichmentReport enrichAll() {
        Instant occurredAt = clock.instant();
        List<LhAnnouncementEnrichmentFailure> failures = new ArrayList<>();
        Set<String> incompleteMappingIds = mappingState.incompleteAnnouncementIdentifiers();
        LhAnnouncementEnrichmentReport report = LhAnnouncementEnrichmentReport.empty();
        for (Map.Entry<String, List<MyHomeAnnouncementSourceView>> group
                : sourcesByAnnouncementWithLh().entrySet()) {
            report = report.plus(enricher.enrich(
                    group.getValue(), failures, occurredAt,
                    incompleteMappingIds.contains(group.getKey())
            ));
        }
        failureStore.reconcileAfterRun(
                failures,
                IngestExecutionScope.currentExecutionId().orElse(null)
        );
        return report;
    }

    private Map<String, List<MyHomeAnnouncementSourceView>> sourcesByAnnouncementWithLh() {
        Map<String, List<MyHomeAnnouncementSourceView>> sources = new LinkedHashMap<>();
        for (MyHomeAnnouncementSourceView source : myHomeSourceRepository.findAllByOrderByIdAsc()) {
            if (blank(source.getPblancId())) {
                continue;
            }
            sources.computeIfAbsent(source.getPblancId().strip(), ignored -> new ArrayList<>())
                    .add(source);
        }
        sources.values().removeIf(group -> group.stream()
                .noneMatch(source -> LhProviderPolicy.isLh(source.getSuplyInsttNm())));
        return sources;
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementEnrichmentFailureResponse> findFailures(int page, int size) {
        return failureStore.pending(page, size).stream()
                .map(LhAnnouncementEnrichmentFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementEnrichmentFailureResponse> findFailureHistory(int page, int size) {
        return failureStore.history(page, size).stream()
                .map(LhAnnouncementEnrichmentFailureResponse::from)
                .toList();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
