package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementLinkResolver.LinkedSource;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementLinkResolver;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhAnnouncementSupplySourceReader;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.domain.LhEnrichmentTargetSources;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentWriter.LhAnnouncementEnrichmentWriteResult;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementLinkResolutionException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 마이홈 공고 하나를 LH 원천으로 보강한다. 보강할 수 있는 공고인지(공고 존재, LH 기관, 공통값, 공급유형, LH 원천 연결)를
 * 차례로 확인하고, 거절하면 이유를 failures에 담아 실패 보고서를 돌려준다.
 */
@Component
@RequiredArgsConstructor
class LhAnnouncementEnricher {

    private final AnnouncementRepository announcementRepository;
    private final LhAnnouncementSupplySourceReader supplySourceRepository;
    private final LhEnrichmentMappingState mappingState;
    private final LhAnnouncementEnrichmentSources enrichmentSources;
    private final LhAnnouncementEnrichmentWriter writer;
    private final LhAnnouncementLinkResolver linkResolver;
    private final LhSupplyRowLinks supplyLinks;

    LhAnnouncementEnrichmentReport enrich(
            List<MyHomeAnnouncementSourceView> sources,
            List<LhAnnouncementEnrichmentFailure> failures,
            Instant occurredAt,
            boolean mappingIncomplete
    ) {
        List<MyHomeAnnouncementSourceView> lhSources = LhEnrichmentTargetSources.select(sources);
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
        return enrichLinkedAnnouncement(announcement, linked, sources, failures, occurredAt, mappingIncomplete);
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
                    announcement, data, Set.of(), LhEnrichmentTargetSources.excludedSourceKeys(sources)
            );
            addSupplyFailures(source, panId, result.failures(), failures, occurredAt);
            return result.report();
        } catch (LhAnnouncementEnrichmentRejectedException exception) {
            return reject(source, panId, exception.reason(), exception.getMessage(), failures, occurredAt);
        }
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
}
