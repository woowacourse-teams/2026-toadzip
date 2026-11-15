package com.toadzip.backend.ingest.quality.service;

import com.toadzip.backend.announcement.domain.SupplySheet;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.service.AnnouncementSupplies;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementCollectedAtReader;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.CollectionCoverage;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Connection;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Coverage;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import com.toadzip.backend.ingest.quality.repository.LhAnnouncementQualityStore;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LhAnnouncementQualityService {

    private final LhAnnouncementQualityStore store;
    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementCollectedAtReader collectedAtReader;
    private final LhAnnouncementCollectionLinkRepository linkRepository;
    private final AnnouncementRepository announcements;
    private final AnnouncementSupplies supplies;
    private final Clock clock;

    public LhAnnouncementQualityService(LhAnnouncementQualityStore store,
            LhAnnouncementCollectionCandidateResolver candidateResolver,
            LhAnnouncementCollectedAtReader collectedAtReader,
            LhAnnouncementCollectionLinkRepository linkRepository, AnnouncementRepository announcements,
            AnnouncementSupplies supplies, Clock clock) {
        this.store = store;
        this.candidateResolver = candidateResolver;
        this.collectedAtReader = collectedAtReader;
        this.linkRepository = linkRepository;
        this.announcements = announcements;
        this.supplies = supplies;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LhAnnouncementQualityResponse snapshot() {
        Instant now = clock.instant();
        List<Candidate> targets = candidateResolver.resolveCatalogTargets();
        Set<String> requestHashes = targets.stream()
                .map(candidate -> candidate.request().requestHash()).collect(Collectors.toSet());
        Set<String> linkedKeys = new HashSet<>();
        linkedKeys.addAll(linkedKeys(targets, ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY));
        linkedKeys.addAll(linkedKeys(targets, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL));
        Set<String> unlinkedCatalogSourceKeys = targets.stream()
                .map(Candidate::sourceAnnouncementKey)
                .filter(key -> !linkedKeys.contains(key))
                .map(key -> key.substring(LhAnnouncementCollectionCandidateResolver.CATALOG_SOURCE_PREFIX.length()))
                .collect(Collectors.toSet());
        List<SupplySheet> sheets = supplies.read(announcements.findAllByProviderAndAdminDeletedFalse(AgencyCode.LH))
                .values().stream().map(AnnouncementSupplies.AnnouncementSupply::sheet).toList();
        return store.snapshot(now, connection(sheets), amounts(sheets),
                collectionCoverage(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, requestHashes),
                collectionCoverage(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, requestHashes),
                unlinkedCatalogSourceKeys);
    }

    /**
     * 공고 공급행(정본 또는 준비 데이터를 공급표로 바꾼 행)의 단지·주택타입 연결 수. 공급행에는 미연결 사유를 두지 않아 미연결 행은 모두
     * '미연결 사유 없음'이다.
     */
    private static Connection connection(List<SupplySheet> sheets) {
        long total = 0;
        long complexLinked = 0;
        long typeLinked = 0;
        long unlinked = 0;
        for (SupplySheet sheet : sheets) {
            for (SupplySheet.Row row : sheet.rows()) {
                total++;
                complexLinked += row.complex() == null ? 0 : 1;
                typeLinked += row.housingType() == null ? 0 : 1;
                unlinked += row.complex() == null || row.housingType() == null ? 1 : 0;
            }
        }
        return new Connection(total, complexLinked, typeLinked, unlinked == 0 ? Map.of() : Map.of("미연결 사유 없음", unlinked));
    }

    /** 보증금과 월세가 모두 있는 칸이 하나라도 있는 공급행 수. */
    private static Coverage amounts(List<SupplySheet> sheets) {
        long total = 0;
        long fulfilled = 0;
        for (SupplySheet sheet : sheets) {
            for (SupplySheet.Row row : sheet.rows()) {
                total++;
                boolean priced = sheet.cells(row.id()).stream().map(sheet::lease).flatMap(Optional::stream)
                        .anyMatch(lease -> lease.amounts().rentalDeposit() != null && lease.amounts().monthlyRent() != null);
                fulfilled += priced ? 1 : 0;
            }
        }
        return new Coverage(total, fulfilled);
    }

    private CollectionCoverage collectionCoverage(ExternalDataSource source, Set<String> requestHashes) {
        if (requestHashes.isEmpty()) {
            return new CollectionCoverage(0, 0, null);
        }
        var collected = collectedAtReader.find(source, requestHashes);
        Instant latest = collected.values().stream().max(Instant::compareTo).orElse(null);
        return new CollectionCoverage(requestHashes.size(), collected.size(), latest);
    }

    /** 공고목록 수집 키로 저장된 연결 중 현재 조회 조건과 일치하는 것만 연결 성공으로 본다. */
    private Set<String> linkedKeys(List<Candidate> targets, ExternalDataSource source) {
        Map<String, Candidate> candidates = new HashMap<>();
        targets.forEach(candidate -> candidates.put(candidate.sourceAnnouncementKey(), candidate));
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return linkRepository.findAllBySourceAndSourceAnnouncementKeyIn(source, candidates.keySet()).stream()
                .filter(link -> {
                    Candidate candidate = candidates.get(link.getSourceAnnouncementKey());
                    return link.matches(candidate.requestDescription(), candidate.panId());
                })
                .map(link -> link.getSourceAnnouncementKey()).collect(Collectors.toSet());
    }
}
