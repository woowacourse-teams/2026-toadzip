package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSource;
import com.toadzip.backend.ingest.collection.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCatalogSourceRepository;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LhAnnouncementCollectionCandidateResolver {

    private static final String NON_LH_PROVIDER_REASON =
            "LH 공급기관이 아닌 마이홈 공고라서 수집 대상이 아닙니다.";
    private static final String UNSUPPORTED_REQUEST_REASON =
            "LH 공고 조회 조건을 지원하지 않아 건너뛰었습니다.";

    private final LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver;
    private final LhAnnouncementCatalogSourceRepository catalogRepository;

    public Resolution resolve(MyHomeAnnouncementSource source) {
        return resolveAll(List.of(source)).getFirst();
    }

    public List<Resolution> resolveAll(List<MyHomeAnnouncementSource> sources) {
        List<String> panIds = sources.stream()
                .filter(this::isLhProvider)
                .map(this::requestOf)
                .flatMap(Optional::stream)
                .map(LhAnnouncementRequest::panId)
                .distinct()
                .toList();
        if (panIds.isEmpty()) {
            return sources.stream().map(source -> resolve(source, List.of())).toList();
        }
        Map<String, List<LhAnnouncementCatalogSource>> catalogByPanId = catalogRepository
                .findAllByPanIdInAndPresentInLatestCatalogTrue(panIds)
                .stream().collect(Collectors.groupingBy(LhAnnouncementCatalogSource::getPanId));
        return sources.stream().map(source -> {
            List<LhAnnouncementCatalogSource> catalog = requestOf(source)
                    .map(request -> catalogByPanId.getOrDefault(request.panId(), List.of()))
                    .orElseGet(List::of);
            return resolve(source, catalog);
        }).toList();
    }

    private Resolution resolve(MyHomeAnnouncementSource source, List<LhAnnouncementCatalogSource> catalog) {
        String sourceAnnouncementKey = sourceAnnouncementKey(source);
        String sourceDescription = sourceDescription(source);
        if (!isLhProvider(source)) {
            return new Skipped(sourceAnnouncementKey, sourceDescription, NON_LH_PROVIDER_REASON);
        }
        Optional<LhAnnouncementRequest> request = requestOf(source);
        if (request.isEmpty()) {
            return new Skipped(sourceAnnouncementKey, sourceDescription, UNSUPPORTED_REQUEST_REASON);
        }
        LhAnnouncementRequest inferred = request.orElseThrow();
        List<LhAnnouncementCatalogSource> matches = catalog.stream()
                .filter(row -> matches(inferred, row))
                .toList();
        if (matches.size() != 1) {
            return new Candidate(sourceAnnouncementKey, sourceDescription, inferred);
        }
        LhAnnouncementCatalogSource row = matches.getFirst();
        LhAnnouncementRequest canonical = new LhAnnouncementRequest(
                row.getPanId(), row.getConnectionSystemDivisionCode(), row.getUpperAnnouncementTypeCode(),
                row.getAnnouncementTypeCode(), row.getSupplyInfoTypeCode()
        );
        return new Candidate(sourceAnnouncementKey, sourceDescription, canonical,
                row.getChangedAt(), row.getCollectedAt());
    }

    private boolean matches(LhAnnouncementRequest request, LhAnnouncementCatalogSource row) {
        return request.panId().equals(row.getPanId())
                && request.connectionSystemDivisionCode().equals(row.getConnectionSystemDivisionCode())
                && request.upperAnnouncementTypeCode().equals(row.getUpperAnnouncementTypeCode())
                && (request.announcementTypeCode() == null
                || request.announcementTypeCode().equals(row.getAnnouncementTypeCode()));
    }

    private boolean isLhProvider(MyHomeAnnouncementSource source) {
        return LhProviderPolicy.isLh(source.getSuplyInsttNm());
    }

    private Optional<LhAnnouncementRequest> requestOf(MyHomeAnnouncementSource source) {
        Optional<String> supplyTypeCode = supplyTypeCodeResolver.resolve(source.getSuplyTyNm());
        if (supplyTypeCode.isEmpty()) {
            return Optional.empty();
        }
        String url = source.getUrl();
        if (url == null || url.isBlank()) {
            url = source.getPcUrl();
        }
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            return LhAnnouncementRequest.from(URI.create(url), supplyTypeCode.orElseThrow());
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String sourceDescription(MyHomeAnnouncementSource source) {
        if (source.getId() == null) {
            return source.getSourceKey();
        }
        return "myhomeAnnouncementSourceId=" + source.getId();
    }

    private String sourceAnnouncementKey(MyHomeAnnouncementSource source) {
        String pblancId = source.getPblancId();
        if (pblancId == null || pblancId.isBlank()) {
            return "source:" + source.getSourceKey();
        }
        return pblancId;
    }

    public sealed interface Resolution permits Candidate, Skipped {

        String sourceAnnouncementKey();

        String sourceDescription();
    }

    public record Candidate(
            String sourceAnnouncementKey,
            String sourceDescription,
            LhAnnouncementRequest request,
            Instant catalogChangedAt,
            Instant catalogCollectedAt
    ) implements Resolution {

        public Candidate(String sourceAnnouncementKey, String sourceDescription, LhAnnouncementRequest request) {
            this(sourceAnnouncementKey, sourceDescription, request, null, null);
        }

        public String requestDescription() {
            return request.requestDescription();
        }

        public String panId() {
            return request.panId();
        }
    }

    public record Skipped(
            String sourceAnnouncementKey,
            String sourceDescription,
            String reason
    ) implements Resolution {
    }
}
