package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSource;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogSourceReader;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdComplexLink;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.Candidate;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.HousingTypeCount;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.ReleaseRequest;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.SaveRequest;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.SavedLink;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.Source;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.View;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdComplexLinkRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHouseholdSource;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 세대수 보강 실패의 LH 원천과 단지 후보를 비교하고, 후보가 하나일 때 관리자가 확인한 연결을 저장·해제한다.
 * 저장·해제는 다음 세대수 보강부터 반영한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhHouseholdComplexLinkService {
    private static final String RESOURCE = "LH_HOUSEHOLD_LINK";

    private final LhHouseholdEnrichmentFailureRepository failures;
    private final LhLeaseCatalogSourceReader sources;
    private final LhHousingTypeHouseholdSourceMapper mapper;
    private final LhHousingTypeHouseholdMatcher matcher;
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final LhHouseholdComplexLinkRepository links;
    private final LhHouseholdComplexLinks fingerprints;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;

    public View view(long failureId) {
        return view(context(failureId));
    }

    @Transactional
    public View save(long failureId, SaveRequest request, String actor) {
        // POST 인터셉터가 트랜잭션 종료까지 전역 수집 잠금을 유지한다.
        IngestExecutionScope.verifyHeld();
        String sourceUrl = sourceUrl(request.sourceUrl());
        var context = verifiedContext(failureId, request.token());
        if (context.candidates().size() != 1 || !context.candidates().getFirst().getId().equals(request.complexId())) {
            throw new AdminDataConflictException("공급유형·세대수·지역이 맞는 후보가 하나인 단지만 연결할 수 있습니다.");
        }
        HousingComplex target = context.candidates().getFirst();
        var link = context.link();
        if (link == null) {
            link = new LhHouseholdComplexLink(context.sourceKey());
        }
        String before = json.writeValueAsString(savedLink(link, context.source()));
        link.confirm(target.getId(), fingerprints.sourceFingerprint(context.source()),
                fingerprints.targetFingerprint(target), sourceUrl, request.reason().strip(), actor);
        links.saveAndFlush(link);
        changes.save(new AdminDataChange(RESOURCE, link.getId(), "SAVE_LH_HOUSEHOLD_LINK", actor, before,
                json.writeValueAsString(Map.of("sourceKey", context.sourceKey(),
                        "link", savedLink(link, context.source())))));
        return view(context(failureId));
    }

    @Transactional
    public View release(long failureId, ReleaseRequest request, String actor) {
        IngestExecutionScope.verifyHeld();
        var context = verifiedContext(failureId, request.token());
        var link = context.link();
        if (link == null) {
            throw new AdminDataConflictException("저장한 단지 연결이 없습니다. 다시 조회해 주세요.");
        }
        long linkId = link.getId();
        String before = json.writeValueAsString(Map.of("sourceKey", context.sourceKey(),
                "link", savedLink(link, context.source())));
        links.delete(link);
        links.flush();
        changes.save(new AdminDataChange(RESOURCE, linkId, "RELEASE_LH_HOUSEHOLD_LINK", actor, before,
                json.writeValueAsString(Map.of("sourceKey", context.sourceKey(),
                        "reason", request.reason().strip()))));
        return view(context(failureId));
    }

    private Context verifiedContext(long failureId, String token) {
        var context = context(failureId);
        if (!token(context).equals(token)) {
            throw new AdminDataConflictException("LH 원천·단지 후보 또는 저장한 연결이 바뀌었습니다. 다시 조회해 주세요.");
        }
        return context;
    }

    private Context context(long failureId) {
        var failure = failures.findById(failureId)
                .orElseThrow(() -> new InvalidIngestRequestException("세대수 보강 오류가 없습니다."));
        String sourceKey = failure.getSourceKey();
        var link = links.findBySourceKey(sourceKey).orElse(null);
        List<LhCatalogSource> group = mapper.group(sources.findAllByOrderBySourceOrderAsc()).entrySet().stream()
                .filter(entry -> entry.getKey().failureKey().equals(sourceKey))
                .map(Map.Entry::getValue).findFirst().orElse(List.of());
        if (group.isEmpty()) {
            return new Context(failureId, sourceKey, null, "현재 LH 임대 목록 원천에 이 단지가 없습니다.", List.of(), link,
                    failure.getAreaName(), failure.getSupplyTypeName(), failure.getComplexName());
        }
        try {
            LhHousingTypeHouseholdSource source = mapper.map(group);
            List<HousingComplex> candidates = matcher.structuralMatches(matcher.index(complexes.findAll()), source)
                    .stream().sorted(Comparator.comparing(HousingComplex::getId)).toList();
            return new Context(failureId, sourceKey, source, null, candidates, link, source.areaName(),
                    source.supplyTypeName(), source.complexName());
        }
        catch (IllegalArgumentException invalid) {
            return new Context(failureId, sourceKey, null, invalid.getMessage(), List.of(), link,
                    failure.getAreaName(), failure.getSupplyTypeName(), failure.getComplexName());
        }
    }

    private SavedLink savedLink(LhHouseholdComplexLink link, LhHousingTypeHouseholdSource source) {
        if (link.getHousingComplexId() == null && link.getReason() == null) {
            return null;
        }
        HousingComplex complex = null;
        if (link.getHousingComplexId() != null) {
            complex = complexes.findById(link.getHousingComplexId()).orElse(null);
        }
        boolean reviewRequired = source == null || fingerprints.validTarget(link, source, complex) == null;
        String complexName = null;
        if (complex != null) {
            complexName = complex.getName();
        }
        return new SavedLink(link.getHousingComplexId(), complexName, link.getSourceUrl(), link.getReason(),
                link.getActor(), link.getUpdatedAt(), reviewRequired);
    }

    private String sourceUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            var uri = URI.create(raw.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new InvalidIngestRequestException("출처는 올바른 HTTP 또는 HTTPS URL로 입력해 주세요.");
        }
        return raw.strip();
    }

    private String token(Context context) {
        var values = new ArrayList<Object>();
        values.add(context.sourceKey());
        if (context.source() != null) {
            values.add(fingerprints.sourceFingerprint(context.source()));
        }
        context.candidates().forEach(candidate -> values.add(fingerprints.targetFingerprint(candidate)));
        if (context.link() != null) {
            values.add(context.link().getId() + ":" + context.link().getVersion());
        }
        return fingerprints.hash(values.toArray());
    }

    private View view(Context context) {
        var source = context.source();
        Integer total = null;
        List<HousingTypeCount> housingTypes = List.of();
        if (source != null) {
            total = source.totalHouseholdCount();
            housingTypes = source.housingTypes().stream()
                    .map(type -> new HousingTypeCount(type.exclusiveArea(), type.totalHouseholdCount())).toList();
        }
        SavedLink saved = null;
        if (context.link() != null) {
            saved = savedLink(context.link(), source);
        }
        return new View(context.failureId(), token(context), new Source(context.areaName(),
                context.supplyTypeName(), context.complexName(), total, housingTypes, context.invalidReason()),
                context.candidates().stream().map(this::candidate).toList(), saved, context.candidates().size() == 1);
    }

    private Candidate candidate(HousingComplex complex) {
        var areas = types.findAllByHousingComplex(complex).stream().map(HousingType::getExclusiveArea)
                .sorted().toList();
        return new Candidate(complex.getId(), complex.getName(), complex.getSupplyType(),
                complex.getTotalHouseholdCount(), complex.getAddress().getRoadAddress(), areas);
    }

    private record Context(long failureId, String sourceKey, LhHousingTypeHouseholdSource source,
            String invalidReason, List<HousingComplex> candidates, LhHouseholdComplexLink link, String areaName,
            String supplyTypeName, String complexName) {
    }
}
