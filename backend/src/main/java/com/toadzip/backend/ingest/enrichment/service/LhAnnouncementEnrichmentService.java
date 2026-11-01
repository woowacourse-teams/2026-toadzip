package com.toadzip.backend.ingest.enrichment.service;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.collection.lh.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementLinkResolver.LinkedSource;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementLinkResolver;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhAnnouncementSupplySourceReader;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentFailureResponse;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureStore;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentWriter.LhAnnouncementEnrichmentWriteResult;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementLinkResolutionException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LhAnnouncementEnrichmentService {

    private final MyHomeAnnouncementSourceReader myHomeSourceRepository;
    private final AnnouncementRepository announcementRepository;
    private final LhAnnouncementSupplySourceReader supplySourceRepository;
    private final LhAnnouncementEnrichmentFailureRepository failureRepository;
    private final LhAnnouncementEnrichmentFailureStore failureStore;
    private final LhEnrichmentMappingState mappingState;
    private final LhAnnouncementEnrichmentSources enrichmentSources;
    private final LhAnnouncementEnrichmentWriter writer;
    private final LhAnnouncementLinkResolver linkResolver;
    private final LhSupplyRowLinks supplyLinks;
    private final Clock clock;

    public LhAnnouncementEnrichmentReport enrichAll() {
        Instant occurredAt = clock.instant();
        List<LhAnnouncementEnrichmentFailure> failures = new ArrayList<>();
        Set<String> incompleteMappingIds = mappingState.incompleteAnnouncementIdentifiers();
        LhAnnouncementEnrichmentReport report = LhAnnouncementEnrichmentReport.empty();
        for (Map.Entry<String, List<MyHomeAnnouncementSourceView>> group
                : sourcesByAnnouncementWithLh().entrySet()) {
            report = report.plus(enrich(
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
        sources.values().removeIf(group -> group.stream().noneMatch(this::isLh));
        return sources;
    }

    private LhAnnouncementEnrichmentReport enrich(
            List<MyHomeAnnouncementSourceView> sources,
            List<LhAnnouncementEnrichmentFailure> failures,
            Instant occurredAt,
            boolean mappingIncomplete
    ) {
        List<MyHomeAnnouncementSourceView> lhSources = MyHomeAnnouncementCurrentSources.select(sources)
                .stream().filter(this::isLh).toList();
        if (lhSources.isEmpty()) {
            return LhAnnouncementEnrichmentReport.empty();
        }
        MyHomeAnnouncementSourceView source = lhSources.getFirst();
        Announcement announcement = announcementRepository
                .findBySourceAnnouncementIdentifier(source.getPblancId())
                .orElse(null);
        if (announcement == null) {
            return reject(source, null, LhAnnouncementEnrichmentFailureReason.ANNOUNCEMENT_NOT_FOUND,
                    "마이홈 기준으로 생성된 LH 공고를 찾을 수 없습니다.", failures, occurredAt);
        }
        if (announcement.getProvider() != AgencyCode.LH) {
            return LhAnnouncementEnrichmentReport.empty();
        }
        String rejectionDetail = mappingState.commonValuesRejection(sources);
        if (rejectionDetail != null) {
            return reject(source, null, LhAnnouncementEnrichmentFailureReason.INVALID_VALUE,
                    rejectionDetail, failures, occurredAt);
        }
        if (announcement.getSupplyType() == RentalType.ETC) {
            return reject(source, null, LhAnnouncementEnrichmentFailureReason.UNSUPPORTED_SUPPLY_TYPE,
                    "지원하지 않는 공급유형의 LH 공고입니다.", failures, occurredAt);
        }
        LinkedSource linked;
        try {
            linked = linkResolver.resolveFirstLinked(lhSources);
        } catch (LhAnnouncementLinkResolutionException exception) {
            return reject(source, null, linkFailureReason(exception), exception.getMessage(), failures, occurredAt);
        }
        return enrichLinkedAnnouncement(
                announcement, linked, sources, lhSources, failures, occurredAt, mappingIncomplete
        );
    }

    private LhAnnouncementEnrichmentFailureReason linkFailureReason(LhAnnouncementLinkResolutionException exception) {
        return switch (exception.reason()) {
            case REQUEST_UNSUPPORTED -> LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_REQUEST_UNSUPPORTED;
            case LINK_NOT_FOUND -> LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_LINK_NOT_FOUND;
            case LINK_MISMATCH -> LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_LINK_MISMATCH;
        };
    }

    private LhAnnouncementEnrichmentReport enrichLinkedAnnouncement(
            Announcement announcement,
            LinkedSource linked,
            List<MyHomeAnnouncementSourceView> sources,
            List<MyHomeAnnouncementSourceView> currentLhSources,
            List<LhAnnouncementEnrichmentFailure> failures,
            Instant occurredAt,
            boolean mappingIncomplete
    ) {
        MyHomeAnnouncementSourceView source = linked.source();
        String panId = linked.request().panId();
        List<LhAnnouncementSupplySourceView> supplies = supplySourceRepository
                .findAllByPanIdAndRequestHashOrderBySourceOrderAsc(panId, linked.request().requestHash());
        try {
            LhAnnouncementEnrichmentData data = enrichmentSources.read(linked.request(), supplies, supplyLinks
                    .states(source.getPblancId(), MyHomeAnnouncementCurrentSources.select(sources), supplies));
            if (mappingIncomplete && announcement.getLhPanId() != null) {
                return reject(source, panId, LhAnnouncementEnrichmentFailureReason.INVALID_VALUE,
                        "마이홈 공고 매핑 실패가 남아 LH 보강을 보류했습니다.", failures, occurredAt);
            }
            LhAnnouncementEnrichmentWriteResult result = writer.write(
                    announcement, data, Set.of(), sourceKeysExcludedFromLhEnrichment(sources, currentLhSources)
            );
            addSupplyFailures(source, panId, result.failures(), failures, occurredAt);
            return result.report();
        } catch (LhAnnouncementEnrichmentRejectedException exception) {
            return reject(source, panId, exception.reason(), exception.getMessage(), failures, occurredAt);
        }
    }

    private Set<String> sourceKeysExcludedFromLhEnrichment(
            List<MyHomeAnnouncementSourceView> sources,
            List<MyHomeAnnouncementSourceView> currentLhSources
    ) {
        Set<String> currentLhSourceKeys = currentLhSources.stream()
                .map(MyHomeAnnouncementSourceView::getSourceKey).collect(Collectors.toSet());
        return sources.stream().map(MyHomeAnnouncementSourceView::getSourceKey)
                .filter(key -> !currentLhSourceKeys.contains(key))
                .collect(Collectors.toSet());
    }

    private void addSupplyFailures(
            MyHomeAnnouncementSourceView source,
            String panId,
            List<LhSupplyMatchingFailureData> supplyFailures,
            List<LhAnnouncementEnrichmentFailure> failures,
            Instant occurredAt
    ) {
        for (LhSupplyMatchingFailureData failure : supplyFailures) {
            failures.add(LhAnnouncementEnrichmentFailure.create(
                    failure.source().sourceIdentifier(), source.getPblancId(), panId,
                    failure.reason(), failure.detail(), occurredAt
            ));
        }
    }

    private LhAnnouncementEnrichmentReport reject(
            MyHomeAnnouncementSourceView source,
            String panId,
            LhAnnouncementEnrichmentFailureReason reason,
            String detail,
            List<LhAnnouncementEnrichmentFailure> failures,
            Instant occurredAt
    ) {
        failures.add(LhAnnouncementEnrichmentFailure.create(
                source.getSourceKey(), source.getPblancId(), panId, reason, detail, occurredAt
        ));
        return LhAnnouncementEnrichmentReport.failed();
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementEnrichmentFailureResponse> findFailures(int page, int size) {
        return failureRepository.findAllByStatusOrderBySourceKeyAscIdAsc(
                        PENDING,
                        PageRequest.of(page, size)
                ).stream()
                .map(LhAnnouncementEnrichmentFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementEnrichmentFailureResponse> findFailures() {
        return failureRepository.findAllByStatusOrderBySourceKeyAsc(PENDING).stream()
                .map(LhAnnouncementEnrichmentFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementEnrichmentFailureResponse> findFailureHistory(int page, int size) {
        return failureRepository.findAllByOrderBySourceKeyAscIdAsc(PageRequest.of(page, size)).stream()
                .map(LhAnnouncementEnrichmentFailureResponse::from)
                .toList();
    }

    private boolean isLh(MyHomeAnnouncementSourceView source) {
        return LhProviderPolicy.isLh(source.getSuplyInsttNm());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
