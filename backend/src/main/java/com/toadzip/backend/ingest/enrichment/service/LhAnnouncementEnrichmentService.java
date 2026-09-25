package com.toadzip.backend.ingest.enrichment.service;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementDetailSourceRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementSupplySourceRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementLinkResolutionException;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementLinkResolver;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentFailureResponse;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentExecutionLock;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureStore;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.failure.service.IngestExecutionContext;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementCommonValuesMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class LhAnnouncementEnrichmentService {

    private final MyHomeAnnouncementSourceRepository myHomeSourceRepository;
    private final AnnouncementRepository announcementRepository;
    private final LhAnnouncementDetailSourceRepository detailSourceRepository;
    private final LhAnnouncementSupplySourceRepository supplySourceRepository;
    private final LhAnnouncementEnrichmentFailureRepository failureRepository;
    private final LhAnnouncementEnrichmentFailureStore failureStore;
    private final MyHomeAnnouncementMappingFailureRepository mappingFailureRepository;
    private final LhAnnouncementEnrichmentExecutionLock executionLock;
    private final LhAnnouncementEnrichmentMapper mapper;
    private final LhAnnouncementEnrichmentWriter writer;
    private final LhAnnouncementLinkResolver linkResolver;
    private final MyHomeAnnouncementCommonValuesMapper commonValuesMapper;
    private final Clock clock;

    public LhAnnouncementEnrichmentService(
            MyHomeAnnouncementSourceRepository myHomeSourceRepository,
            AnnouncementRepository announcementRepository,
            LhAnnouncementDetailSourceRepository detailSourceRepository,
            LhAnnouncementSupplySourceRepository supplySourceRepository,
            LhAnnouncementEnrichmentFailureRepository failureRepository,
            LhAnnouncementEnrichmentFailureStore failureStore,
            MyHomeAnnouncementMappingFailureRepository mappingFailureRepository,
            LhAnnouncementEnrichmentExecutionLock executionLock,
            LhAnnouncementEnrichmentMapper mapper,
            LhAnnouncementEnrichmentWriter writer,
            LhAnnouncementLinkResolver linkResolver,
            MyHomeAnnouncementCommonValuesMapper commonValuesMapper,
            Clock clock
    ) {
        this.myHomeSourceRepository = myHomeSourceRepository;
        this.announcementRepository = announcementRepository;
        this.detailSourceRepository = detailSourceRepository;
        this.supplySourceRepository = supplySourceRepository;
        this.failureRepository = failureRepository;
        this.failureStore = failureStore;
        this.mappingFailureRepository = mappingFailureRepository;
        this.executionLock = executionLock;
        this.mapper = mapper;
        this.writer = writer;
        this.linkResolver = linkResolver;
        this.commonValuesMapper = commonValuesMapper;
        this.clock = clock;
    }

    public LhAnnouncementEnrichmentReport enrichAll() {
        return executionLock.tryRun(this::enrichAllUnlocked).orElseThrow(this::alreadyRunning);
    }

    public List<LhAnnouncementEnrichmentFailure> enrichForAtomicMapping(
            List<MyHomeAnnouncementSource> sources,
            Set<Long> changedHousingTypeRows
    ) {
        List<LhAnnouncementEnrichmentFailure> failures = new ArrayList<>();
        enrich(sources, failures, clock.instant(), changedHousingTypeRows, false);
        return List.copyOf(failures);
    }

    private LhAnnouncementEnrichmentReport enrichAllUnlocked() {
        Instant occurredAt = clock.instant();
        List<LhAnnouncementEnrichmentFailure> failures = new ArrayList<>();
        Set<String> incompleteMappingIds = mappingFailureRepository.findAllByStatus(PENDING)
                .stream()
                .map(failure -> failure.getSourceAnnouncementIdentifier())
                .filter(identifier -> !blank(identifier))
                .collect(Collectors.toSet());
        LhAnnouncementEnrichmentReport report = LhAnnouncementEnrichmentReport.empty();
        for (Map.Entry<String, List<MyHomeAnnouncementSource>> group
                : sourcesByAnnouncementWithLh().entrySet()) {
            report = report.plus(enrich(
                    group.getValue(), failures, occurredAt, Set.of(),
                    incompleteMappingIds.contains(group.getKey())
            ));
        }
        failureStore.replaceAll(
                failures,
                IngestExecutionContext.currentExecutionId().orElse(null)
        );
        return report;
    }

    private Map<String, List<MyHomeAnnouncementSource>> sourcesByAnnouncementWithLh() {
        Map<String, List<MyHomeAnnouncementSource>> sources = new LinkedHashMap<>();
        for (MyHomeAnnouncementSource source : myHomeSourceRepository.findAllByOrderByIdAsc()) {
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
            List<MyHomeAnnouncementSource> sources,
            List<LhAnnouncementEnrichmentFailure> failures,
            Instant occurredAt,
            Set<Long> changedHousingTypeRows,
            boolean mappingIncomplete
    ) {
        List<MyHomeAnnouncementSource> lhSources = sources.stream().filter(this::isLh).toList();
        MyHomeAnnouncementSource source = lhSources.getFirst();
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
        String rejectionDetail = commonValuesMapper.rejectionDetail(sources);
        if (rejectionDetail != null) {
            return reject(source, null, LhAnnouncementEnrichmentFailureReason.INVALID_VALUE,
                    rejectionDetail, failures, occurredAt);
        }
        if (announcement.getSupplyType() == RentalType.ETC) {
            return reject(source, null, LhAnnouncementEnrichmentFailureReason.UNSUPPORTED_SUPPLY_TYPE,
                    "지원하지 않는 공급유형의 LH 공고입니다.", failures, occurredAt);
        }
        LhAnnouncementRequest request;
        try {
            var linked = linkResolver.resolveFirstLinked(lhSources);
            source = linked.source();
            request = linked.request();
        }
        catch (LhAnnouncementLinkResolutionException exception) {
            LhAnnouncementEnrichmentFailureReason reason = switch (exception.reason()) {
                case REQUEST_UNSUPPORTED -> LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_REQUEST_UNSUPPORTED;
                case LINK_NOT_FOUND -> LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_LINK_NOT_FOUND;
                case LINK_MISMATCH -> LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_LINK_MISMATCH;
            };
            return reject(source, null, reason, exception.getMessage(), failures, occurredAt);
        }
        String panId = request.panId();
        String requestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(request.requestDescription());
        List<LhAnnouncementDetailSource> details = detailSourceRepository
                .findAllByPanIdAndRequestHashOrderBySourceOrderAsc(panId, requestHash);
        if (details.isEmpty()) {
            return reject(source, panId, LhAnnouncementEnrichmentFailureReason.LH_DETAIL_SOURCE_NOT_FOUND,
                    "연결된 LH 공고 상세 원본이 없습니다.", failures, occurredAt);
        }
        List<LhAnnouncementSupplySource> supplies = supplySourceRepository
                .findAllByPanIdAndRequestHashOrderBySourceOrderAsc(panId, requestHash);
        try {
            LhAnnouncementEnrichmentData data = mapper.map(panId, details, supplies);
            if (mappingIncomplete && announcement.getLhPanId() != null) {
                return reject(source, panId, LhAnnouncementEnrichmentFailureReason.INVALID_VALUE,
                        "마이홈 공고 매핑 실패가 남아 LH 보강을 보류했습니다.", failures, occurredAt);
            }
            LhAnnouncementEnrichmentWriteResult result = writer.write(
                    announcement, data, changedHousingTypeRows
            );
            addSupplyFailures(source, panId, result.failures(), failures, occurredAt);
            return result.report();
        }
        catch (LhAnnouncementEnrichmentRejectedException exception) {
            return reject(source, panId, exception.reason(), exception.getMessage(), failures, occurredAt);
        }
    }

    private void addSupplyFailures(
            MyHomeAnnouncementSource source,
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
            MyHomeAnnouncementSource source,
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

    private boolean isLh(MyHomeAnnouncementSource source) {
        return LhProviderPolicy.isLh(source.getSuplyInsttNm());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private IngestAlreadyRunningException alreadyRunning() {
        log.warn("LH 공고 보강이 이미 실행 중이므로 중복 실행을 건너뜁니다.");
        return new IngestAlreadyRunningException("LH 공고 보강이 이미 실행 중입니다.");
    }
}
