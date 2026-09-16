package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
    private final LhAnnouncementEnrichmentExecutionLock executionLock;
    private final LhAnnouncementEnrichmentMapper mapper;
    private final LhAnnouncementEnrichmentWriter writer;
    private final LhAnnouncementLinkResolver linkResolver;
    private final Clock clock;

    public LhAnnouncementEnrichmentService(
            MyHomeAnnouncementSourceRepository myHomeSourceRepository,
            AnnouncementRepository announcementRepository,
            LhAnnouncementDetailSourceRepository detailSourceRepository,
            LhAnnouncementSupplySourceRepository supplySourceRepository,
            LhAnnouncementEnrichmentFailureRepository failureRepository,
            LhAnnouncementEnrichmentFailureStore failureStore,
            LhAnnouncementEnrichmentExecutionLock executionLock,
            LhAnnouncementEnrichmentMapper mapper,
            LhAnnouncementEnrichmentWriter writer,
            LhAnnouncementLinkResolver linkResolver,
            Clock clock
    ) {
        this.myHomeSourceRepository = myHomeSourceRepository;
        this.announcementRepository = announcementRepository;
        this.detailSourceRepository = detailSourceRepository;
        this.supplySourceRepository = supplySourceRepository;
        this.failureRepository = failureRepository;
        this.failureStore = failureStore;
        this.executionLock = executionLock;
        this.mapper = mapper;
        this.writer = writer;
        this.linkResolver = linkResolver;
        this.clock = clock;
    }

    public LhAnnouncementEnrichmentReport enrichAll() {
        return executionLock.tryRun(this::enrichAllUnlocked).orElseThrow(this::alreadyRunning);
    }

    private LhAnnouncementEnrichmentReport enrichAllUnlocked() {
        Instant occurredAt = clock.instant();
        List<LhAnnouncementEnrichmentFailure> failures = new ArrayList<>();
        LhAnnouncementEnrichmentReport report = LhAnnouncementEnrichmentReport.empty();
        for (MyHomeAnnouncementSource source : lhSourcesByAnnouncement().values()) {
            report = report.plus(enrich(source, failures, occurredAt));
        }
        failureStore.replaceAll(failures);
        return report;
    }

    private Map<String, MyHomeAnnouncementSource> lhSourcesByAnnouncement() {
        Map<String, MyHomeAnnouncementSource> sources = new LinkedHashMap<>();
        for (MyHomeAnnouncementSource source : myHomeSourceRepository.findAll()) {
            if (!isLh(source) || blank(source.getPblancId())) {
                continue;
            }
            sources.putIfAbsent(source.getPblancId().strip(), source);
        }
        return sources;
    }

    private LhAnnouncementEnrichmentReport enrich(
            MyHomeAnnouncementSource source,
            List<LhAnnouncementEnrichmentFailure> failures,
            Instant occurredAt
    ) {
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
        if (announcement.getSupplyType() == RentalType.ETC) {
            return reject(source, null, LhAnnouncementEnrichmentFailureReason.UNSUPPORTED_SUPPLY_TYPE,
                    "지원하지 않는 공급유형의 LH 공고입니다.", failures, occurredAt);
        }
        String panId;
        try {
            panId = linkResolver.resolve(source);
        }
        catch (LhAnnouncementLinkResolutionException exception) {
            LhAnnouncementEnrichmentFailureReason reason = switch (exception.reason()) {
                case REQUEST_UNSUPPORTED -> LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_REQUEST_UNSUPPORTED;
                case LINK_NOT_FOUND -> LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_LINK_NOT_FOUND;
                case LINK_MISMATCH -> LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_LINK_MISMATCH;
            };
            return reject(source, null, reason, exception.getMessage(), failures, occurredAt);
        }
        List<LhAnnouncementDetailSource> details = detailSourceRepository.findAllByPanIdOrderBySourceOrderAsc(panId);
        if (details.isEmpty()) {
            return reject(source, panId, LhAnnouncementEnrichmentFailureReason.LH_DETAIL_SOURCE_NOT_FOUND,
                    "연결된 LH 공고 상세 원본이 없습니다.", failures, occurredAt);
        }
        List<LhAnnouncementSupplySource> supplies = supplySourceRepository.findAllByPanIdOrderBySourceOrderAsc(panId);
        if (supplies.isEmpty()) {
            return reject(source, panId, LhAnnouncementEnrichmentFailureReason.LH_SUPPLY_SOURCE_NOT_FOUND,
                    "연결된 LH 공고 공급 원본이 없습니다.", failures, occurredAt);
        }
        try {
            LhAnnouncementEnrichmentData data = mapper.map(panId, details, supplies);
            LhAnnouncementEnrichmentWriteResult result = writer.write(announcement, data);
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
    public List<LhAnnouncementEnrichmentFailureResponse> findFailures() {
        return failureRepository.findAllByOrderBySourceKeyAsc().stream()
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
