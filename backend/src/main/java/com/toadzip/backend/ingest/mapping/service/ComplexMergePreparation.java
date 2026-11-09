package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.mapping.service.ComplexMergeChecks.conflict;
import static com.toadzip.backend.ingest.mapping.service.ComplexMergeChecks.require;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.domain.LhLeaseCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.repository.LhLeaseCatalogSourceReader;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdMatcher;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMerge;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergePreviewRequest;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergePreviewResponse;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMergeStore;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeComplexMappingData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 고른 단지들을 하나로 합쳐도 되는지 확인하고, 합치기 전 제품 상태와 원천·LH 근거로 미리보기 검증값을 만든다.
 * 실행할 때는 근거와 참조를 잠근 뒤 같은 확인을 다시 해 미리보기 이후 바뀐 것을 잡는다.
 */
@Component
@RequiredArgsConstructor
class ComplexMergePreparation {

    private final HousingComplexRepository complexes;
    private final MyHomeComplexLinkRepository links;
    private final MyHomeComplexSourceReader sources;
    private final LhLeaseCatalogSourceReader lhSources;
    private final MyHomeComplexSourceMapper mapper;
    private final LhHousingTypeHouseholdMatcher matcher;
    private final MyHomeComplexMergeStore store;

    PreparedMerge prepare(ComplexMergePreviewRequest request, boolean lock) {
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
            List<MyHomeComplexSourceView> rows = sourceRows(link);
            observations.add(observation(complex, link, rows));
            sourceRowIds.addAll(rows.stream().map(MyHomeComplexSourceView::getId).toList());
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
        require(store.countAdminRecords(ids.subList(1, ids.size())) == 0,
                "통합할 단지에 저장 매칭·LH 세대수 연결·세대수 결정·검증·비교 기록이 있습니다. "
                        + "통합 복구로 되돌릴 수 없으므로 기록을 정리한 뒤 다시 확인해야 합니다.");
        LhLeaseCatalogSourceView selected = lhSources.findById(request.lhSourceId())
                .orElseThrow(() -> conflict("선택한 LH 원천이 없습니다."));
        long sum = observations.stream().mapToLong(ComplexMergePreviewResponse.Source::householdCount).sum();
        List<LhLeaseCatalogSourceView> evidenceRows = lhEvidence(products.getFirst(), selected, sum);
        if (lock) {
            store.lockReferences(ids);
        }
        String before = store.state(ids);
        String evidence = store.evidence(sourceRowIds, evidenceRows.stream().map(LhLeaseCatalogSourceView::getId).toList());
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

    private List<MyHomeComplexSourceView> sourceRows(MyHomeComplexLink link) {
        String identifier = link.getSourceComplexIdentifier();
        long hsmpSn = Long.parseLong(identifier.substring(0, identifier.indexOf(':')));
        return sources.findAllByHsmpSnIn(List.of(hsmpSn)).stream()
                .filter(source -> identifier.equals(mapper.sourceComplexIdentifier(source))).toList();
    }

    private ComplexMergePreviewResponse.Source observation(
            HousingComplex product, MyHomeComplexLink link, List<MyHomeComplexSourceView> rows
    ) {
        require(!rows.isEmpty(), "연결할 마이홈 원천이 없습니다.");
        MyHomeComplexMappingData data;
        try {
            data = mapper.map(link.getSourceComplexIdentifier(), rows);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            throw conflict(exception.getMessage());
        }
        require(data.totalHouseholdCount() > 0 && Objects.equals(data.totalHouseholdCount(), product.getTotalHouseholdCount())
                && data.matchesVerifiedProduct(product),
                "현재 원천과 제품의 단지 공통값 또는 세대수가 다릅니다.");
        List<Instant> times = rows.stream().map(MyHomeComplexSourceView::getCollectedAt).distinct().toList();
        require(times.size() == 1 && times.getFirst() != null, "원천의 수집 기준 시각을 확인할 수 없습니다.");
        return new ComplexMergePreviewResponse.Source(link.getSourceComplexIdentifier(),
                data.totalHouseholdCount(), times.getFirst());
    }

    private List<LhLeaseCatalogSourceView> lhEvidence(HousingComplex product, LhLeaseCatalogSourceView selected, long sum) {
        require(matcher.hasExactIdentity(product, selected.getComplexLabel(), selected.getAreaName(),
                selected.getSupplyTypeName()), "LH 근거의 이름·지역·공급유형이 일치하지 않습니다.");
        List<LhLeaseCatalogSourceView> rows = lhSources.findAllByOrderBySourceOrderAsc().stream()
                .filter(row -> Objects.equals(row.getComplexLabel(), selected.getComplexLabel())
                        && Objects.equals(row.getAreaName(), selected.getAreaName())
                        && Objects.equals(row.getSupplyTypeName(), selected.getSupplyTypeName())).toList();
        for (LhLeaseCatalogSourceView row : rows) {
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

    record PreparedMerge(
            ComplexMergePreviewResponse response, List<HousingComplex> complexes, List<MyHomeComplexLink> links,
            String before, String evidence
    ) {
    }
}
