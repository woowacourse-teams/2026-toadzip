package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.collection.domain.LhCatalogSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.repository.LhCatalogSourceRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeComplexSourceRepository;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdMatcher;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMerge;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergePreviewRequest;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergePreviewResponse;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergeRequest;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergeResponse;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergeCandidateResponse;
import com.toadzip.backend.ingest.mapping.exception.ComplexMergeConflictException;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingExecutionLock;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMergeRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMergeStore;
import com.toadzip.backend.ingest.mapping.repository.ComplexMergeCandidateRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MyHomeComplexMergeService {

    private final HousingComplexRepository complexes;
    private final MyHomeComplexLinkRepository links;
    private final MyHomeComplexMergeRepository merges;
    private final MyHomeComplexSourceRepository sources;
    private final LhCatalogSourceRepository lhSources;
    private final MyHomeComplexSourceMapper mapper;
    private final LhHousingTypeHouseholdMatcher matcher;
    private final MyHomeComplexMergeStore store;
    private final MyHomeComplexMappingExecutionLock executionLock;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MyHomeComplexMergeService(
            HousingComplexRepository complexes, MyHomeComplexLinkRepository links,
            MyHomeComplexMergeRepository merges, MyHomeComplexSourceRepository sources,
            LhCatalogSourceRepository lhSources, MyHomeComplexSourceMapper mapper,
            LhHousingTypeHouseholdMatcher matcher, MyHomeComplexMergeStore store,
            MyHomeComplexMappingExecutionLock executionLock, PlatformTransactionManager transactionManager, Clock clock
    ) {
        this.complexes = complexes;
        this.links = links;
        this.merges = merges;
        this.sources = sources;
        this.lhSources = lhSources;
        this.mapper = mapper;
        this.matcher = matcher;
        this.store = store;
        this.executionLock = executionLock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ComplexMergeCandidateResponse> candidates(long afterId, int size) {
        var candidates = store.candidates(afterId, size);
        var products = complexes.findAllById(candidates.stream()
                .map(ComplexMergeCandidateRow::representativeId).toList());
        List<LhCatalogSource> catalog = lhSources.findAllByOrderBySourceOrderAsc();
        return candidates.stream().map(candidate -> {
            HousingComplex product = products.stream()
                    .filter(complex -> complex.getId() == candidate.representativeId()).findFirst().orElseThrow();
            return new ComplexMergeCandidateResponse(
                    candidate.representativeId(), candidate.name(), candidate.roadAddress(), candidate.pnu(),
                    candidate.provider(), candidate.supplyType(), candidate.sources().stream().map(source ->
                            new ComplexMergeCandidateResponse.Source(
                                    source.complexId(), source.sourceIdentifier(), source.householdCount())).toList(),
                    catalog.stream().filter(source -> matcher.hasExactIdentity(product,
                                    source.getComplexLabel(), source.getAreaName(), source.getSupplyTypeName()))
                            .map(source -> new ComplexMergeCandidateResponse.LhEvidence(
                                    source.getId(), source.getComplexTotalUnitCount(), source.getCollectedAt()))
                            .toList());
        }).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ComplexMergePreviewResponse preview(ComplexMergePreviewRequest request) {
        return prepare(request, false).response();
    }

    public ComplexMergeResponse merge(ComplexMergeRequest request, String actor) {
        return executionLock.tryRun(() -> transaction.execute(status -> mergeLocked(request, actor)))
                .orElseThrow(() -> new IngestAlreadyRunningException("단지 정제 또는 통합이 실행 중입니다."));
    }

    public ComplexMergeResponse revert(UUID id, String actor) {
        return executionLock.tryRun(() -> transaction.execute(status -> revertLocked(id, actor)))
                .orElseThrow(() -> new IngestAlreadyRunningException("단지 정제 또는 통합이 실행 중입니다."));
    }

    @Transactional(readOnly = true)
    public ComplexMergeResponse get(UUID id) {
        return response(findMerge(id));
    }

    private ComplexMergeResponse mergeLocked(ComplexMergeRequest request, String actor) {
        require(request.confirmedSameComplex(), "동일 실제 단지와 공급 범위를 명시적으로 확인해야 합니다.");
        MyHomeComplexMerge previous = merges.findById(request.operationId()).orElse(null);
        if (previous != null) {
            require(previous.getPreviewHash().equals(request.expectedHash())
                    && previous.getComplexIds().equals(request.complexIds().stream().sorted().toList())
                    && previous.getReason().equals(request.reason()), "같은 작업 ID에 다른 통합 요청을 사용할 수 없습니다.");
            return response(previous);
        }
        PreparedMerge prepared = prepare(
                new ComplexMergePreviewRequest(request.complexIds(), request.lhSourceId()), true);
        ComplexMergePreviewResponse preview = prepared.response();
        require(preview.previewHash().equals(request.expectedHash()),
                "미리보기 이후 원천 또는 제품 데이터가 바뀌었습니다. 다시 확인해야 합니다.");
        MyHomeComplexMerge merge = MyHomeComplexMerge.verified(
                request.operationId(), preview.complexIds(), preview.adoptedHouseholdCount(),
                prepared.before(), prepared.evidence(), preview.previewHash(), request.reason(), actor, clock.instant()
        );
        merges.saveAndFlush(merge);
        HousingComplex representative = prepared.complexes().getFirst();
        representative.adoptVerifiedMerge(
                preview.adoptedHouseholdCount(),
                MyHomeComplexMerge.singleManualValue(prepared.complexes(), HousingComplex::getImageUrl),
                MyHomeComplexMerge.singleManualValue(
                        prepared.complexes(), HousingComplex::getRecentOneYearMoveOutCount));
        for (int index = 0; index < prepared.links().size(); index++) {
            prepared.links().get(index).verifyAndConnect(
                    representative, merge.getId(), preview.sources().get(index).householdCount());
        }
        links.flush();
        store.transferAndRemove(representative.getId(), preview.complexIds().subList(1, preview.complexIds().size()),
                merge.getId());
        merge.complete(store.state(preview.complexIds()));
        return response(merges.save(merge));
    }

    private ComplexMergeResponse revertLocked(UUID id, String actor) {
        MyHomeComplexMerge merge = findMerge(id);
        if (merge.getRevertedAt() != null) {
            return response(merge);
        }
        require(merge.getSnapshotVersion() == 1, "현재 버전으로 복구할 수 없는 이력입니다.");
        complexes.findByIdForUpdate(merge.getRepresentativeId())
                .orElseThrow(() -> conflict("대표 단지가 사라져 복구할 수 없습니다."));
        store.lockReferences(merge.getComplexIds());
        require(store.sameState(store.state(merge.getComplexIds()), merge.getAfterState()),
                "통합 이후 제품 데이터 또는 참조가 변경되었습니다. 후속 수정을 보존하기 위해 복구를 보류합니다.");
        store.restore(merge.getBeforeState(), id);
        merge.revert(actor, clock.instant());
        return response(merges.save(merge));
    }

    private PreparedMerge prepare(ComplexMergePreviewRequest request, boolean lock) {
        if (lock) {
            store.lockEvidence();
        }
        List<Long> ids = request.complexIds().stream().distinct().sorted().toList();
        require(ids.size() >= 2 && ids.size() <= 20 && ids.size() == request.complexIds().size(),
                "서로 다른 단지 2~20개를 선택해야 합니다.");
        List<HousingComplex> products = new ArrayList<>();
        List<MyHomeComplexLink> sourceLinks = new ArrayList<>();
        List<ComplexMergePreviewResponse.Source> observations = new ArrayList<>();
        List<Long> sourceRowIds = new ArrayList<>();
        for (long id : ids) {
            HousingComplex complex = product(id, lock);
            List<MyHomeComplexLink> connected = links.findAllByHousingComplexId(id);
            require(connected.size() == 1 && connected.getFirst().getMergeId() == null,
                    "단일 마이홈 원천에 연결된 미통합 단지만 선택할 수 있습니다.");
            MyHomeComplexLink link = connected.getFirst();
            List<MyHomeComplexSource> rows = sourceRows(link);
            observations.add(observation(complex, link, rows));
            sourceRowIds.addAll(rows.stream().map(MyHomeComplexSource::getId).toList());
            sourceLinks.add(link);
            products.add(complex);
        }
        require(observations.stream().map(ComplexMergePreviewResponse.Source::collectedAt).distinct().count() == 1,
                "서로 다른 수집 시점의 마이홈 원천입니다. 같은 수집분으로 다시 확인해야 합니다.");
        try {
            MyHomeComplexMerge.requireCompatibleProducts(products);
        }
        catch (IllegalArgumentException exception) {
            throw conflict(exception.getMessage());
        }
        LhCatalogSource selected = lhSources.findById(request.lhSourceId())
                .orElseThrow(() -> conflict("선택한 LH 원천이 없습니다."));
        long sum = observations.stream().mapToLong(ComplexMergePreviewResponse.Source::householdCount).sum();
        List<LhCatalogSource> evidenceRows = lhEvidence(products.getFirst(), selected, sum);
        if (lock) {
            store.lockReferences(ids);
        }
        String before = store.state(ids);
        String evidence = store.evidence(sourceRowIds, evidenceRows.stream().map(LhCatalogSource::getId).toList());
        String hash = hash(before + "\n" + evidence);
        return new PreparedMerge(new ComplexMergePreviewResponse(
                ids.getFirst(), ids, Math.toIntExact(sum), hash, observations, selected.getId(),
                selected.getCollectedAt(), selected.getAreaName(), selected.getComplexLabel()),
                products, sourceLinks, before, evidence);
    }

    private HousingComplex product(long id, boolean lock) {
        if (lock) {
            return complexes.findByIdForUpdate(id).orElseThrow(() -> conflict("선택한 단지가 없습니다."));
        }
        return complexes.findById(id).orElseThrow(() -> conflict("선택한 단지가 없습니다."));
    }

    private List<MyHomeComplexSource> sourceRows(MyHomeComplexLink link) {
        String identifier = link.getSourceComplexIdentifier();
        long hsmpSn = Long.parseLong(identifier.substring(0, identifier.indexOf(':')));
        return sources.findAllByHsmpSnIn(List.of(hsmpSn)).stream()
                .filter(source -> identifier.equals(mapper.sourceComplexIdentifier(source))).toList();
    }

    private ComplexMergePreviewResponse.Source observation(
            HousingComplex product, MyHomeComplexLink link, List<MyHomeComplexSource> rows
    ) {
        require(!rows.isEmpty(), "연결할 마이홈 원천이 없습니다.");
        MyHomeComplexMappingData data;
        try {
            data = mapper.map(link.getSourceComplexIdentifier(), rows);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            throw conflict(exception.getMessage());
        }
        require(data.totalHouseholdCount() > 0 && data.totalHouseholdCount() == product.getTotalHouseholdCount()
                && data.matchesVerifiedProduct(product),
                "현재 원천과 제품의 단지 공통값 또는 세대수가 다릅니다.");
        List<Instant> times = rows.stream().map(MyHomeComplexSource::getCollectedAt).distinct().toList();
        require(times.size() == 1 && times.getFirst() != null, "원천의 수집 기준 시각을 확인할 수 없습니다.");
        return new ComplexMergePreviewResponse.Source(link.getSourceComplexIdentifier(),
                data.totalHouseholdCount(), times.getFirst());
    }

    private List<LhCatalogSource> lhEvidence(HousingComplex product, LhCatalogSource selected, long sum) {
        require(matcher.hasExactIdentity(product, selected.getComplexLabel(), selected.getAreaName(),
                selected.getSupplyTypeName()), "LH 근거의 이름·지역·공급유형이 일치하지 않습니다.");
        List<LhCatalogSource> rows = lhSources.findAllByOrderBySourceOrderAsc().stream()
                .filter(row -> Objects.equals(row.getComplexLabel(), selected.getComplexLabel())
                        && Objects.equals(row.getAreaName(), selected.getAreaName())
                        && Objects.equals(row.getSupplyTypeName(), selected.getSupplyTypeName())).toList();
        for (LhCatalogSource row : rows) {
            require(row.getCollectedAt() != null && row.getCollectedAt().equals(selected.getCollectedAt()),
                    "LH 근거의 수집 시각이 누락되었거나 서로 다릅니다.");
            try {
                int count = Integer.parseInt(row.getComplexTotalUnitCount().replace(",", "").strip());
                require(count > 0 && count == sum, "LH 전체 세대수와 원천별 세대수 범위가 일치하지 않습니다.");
            }
            catch (NumberFormatException | NullPointerException exception) {
                throw conflict("LH 근거의 전체 세대수를 확인할 수 없습니다.");
            }
        }
        return rows;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("통합 미리보기 검증값을 생성하지 못했습니다.", exception);
        }
    }

    private MyHomeComplexMerge findMerge(UUID id) {
        return merges.findById(id).orElseThrow(() -> conflict("통합 이력을 찾을 수 없습니다."));
    }

    private ComplexMergeResponse response(MyHomeComplexMerge merge) {
        return new ComplexMergeResponse(merge.getId(), merge.getRepresentativeId(), merge.getComplexIds(),
                merge.getAdoptedHouseholdCount(), merge.getReason(), merge.getVerifiedBy(), merge.getMergedAt(),
                merge.getRevertedBy(), merge.getRevertedAt(), merge.getEvidence());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw conflict(message);
        }
    }

    private static ComplexMergeConflictException conflict(String message) {
        return new ComplexMergeConflictException(message);
    }

    private record PreparedMerge(
            ComplexMergePreviewResponse response, List<HousingComplex> complexes, List<MyHomeComplexLink> links,
            String before, String evidence
    ) {
    }
}
