package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhLeaseCatalogSourceView;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogSourceReader;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdMatcher;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergeCandidateResponse;
import com.toadzip.backend.ingest.mapping.repository.ComplexMergeCandidateRow;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMergeStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 같은 실제 단지로 보이는 마이홈 단지 후보와, 이름·지역·공급유형이 정확히 같은 LH 카탈로그 근거를 보여 준다. */
@Service
@RequiredArgsConstructor
public class ComplexMergeCandidateService {

    private final HousingComplexRepository complexes;
    private final LhLeaseCatalogSourceReader lhSources;
    private final LhHousingTypeHouseholdMatcher matcher;
    private final MyHomeComplexMergeStore store;

    @Transactional(readOnly = true)
    public List<ComplexMergeCandidateResponse> candidates(long afterId, int size) {
        var candidates = store.candidates(afterId, size);
        var products = complexes.findAllById(candidates.stream()
                .map(ComplexMergeCandidateRow::representativeId).toList());
        List<LhLeaseCatalogSourceView> catalog = lhSources.findAllByOrderBySourceOrderAsc();
        return candidates.stream().map(candidate -> candidateResponse(candidate, products, catalog)).toList();
    }

    private ComplexMergeCandidateResponse candidateResponse(
            ComplexMergeCandidateRow candidate,
            List<HousingComplex> products,
            List<LhLeaseCatalogSourceView> catalog
    ) {
        HousingComplex product = products.stream()
                .filter(complex -> complex.getId() == candidate.representativeId()).findFirst().orElseThrow();
        return new ComplexMergeCandidateResponse(
                candidate.representativeId(), candidate.name(), candidate.roadAddress(), candidate.pnu(),
                candidate.provider(), candidate.supplyType(), candidate.sources().stream().map(source ->
                        new ComplexMergeCandidateResponse.Source(
                                source.complexId(), source.sourceIdentifier(), source.householdCount())).toList(),
                candidateEvidence(product, catalog)
        );
    }

    private List<ComplexMergeCandidateResponse.LhEvidence> candidateEvidence(
            HousingComplex product,
            List<LhLeaseCatalogSourceView> catalog
    ) {
        return catalog.stream().filter(source -> matcher.hasExactIdentity(product,
                        source.getComplexLabel(), source.getAreaName(), source.getSupplyTypeName()))
                .map(source -> new ComplexMergeCandidateResponse.LhEvidence(
                        source.getId(), source.getComplexTotalUnitCount(), source.getCollectedAt()))
                .toList();
    }
}
