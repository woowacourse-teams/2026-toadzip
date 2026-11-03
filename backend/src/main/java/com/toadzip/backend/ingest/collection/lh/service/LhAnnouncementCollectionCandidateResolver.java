package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogSourceReader;
import com.toadzip.backend.ingest.collection.lh.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LH 공고 수집 요청을 정한다. 공고 수집은 최신 LH 공고목록에서 후보를 만든다.
 * 마이홈 공고 원천의 해석({@link #resolveAll})은 지난 마이홈 공고 데이터를 읽는 품질·보정 화면만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class LhAnnouncementCollectionCandidateResolver {

    private static final String NON_LH_PROVIDER_REASON =
            "LH 공급기관이 아닌 마이홈 공고라서 수집 대상이 아닙니다.";
    private static final String UNSUPPORTED_REQUEST_REASON =
            "LH 공고 조회 조건을 지원하지 않아 건너뛰었습니다.";
    /** 공고목록에서 정한 수집 후보의 진행 키 접두사. 마이홈 공고 ID와 겹치지 않는다. */
    public static final String CATALOG_SOURCE_PREFIX = "lh-catalog:";
    private static final Map<String, String> SUPPLY_TYPE_CODE_BY_NAME = Map.of(
            "5년임대", "060",
            "10년임대", "060",
            "50년임대", "061",
            "국민임대", "062",
            "영구임대", "062",
            "행복주택", "063",
            "통합공공임대", "062"
    );

    private final LhAnnouncementCatalogSourceReader catalogRepository;
    private final LhAnnouncementCollectionPolicy collectionPolicy;

    /** 최신 공고목록의 임대주택 공고 중 종료 정책 안의 공고를 목록의 조회 코드 그대로 수집 후보로 만든다. */
    public List<Candidate> resolveCatalogTargets() {
        return catalogRepository.findAllPresentRentalHousingAnnouncements().stream()
                .filter(collectionPolicy::isCollectionTarget)
                .map(this::catalogCandidate)
                .toList();
    }

    /** 종료 정책과 관계없이 최신 공고목록의 임대주택 공고 요청. 관리자 승인·강제 재조회는 종료 정책을 건너뛴다. */
    public List<Candidate> resolveCatalogRequests() {
        return catalogRepository.findAllPresentRentalHousingAnnouncements().stream()
                .map(this::catalogCandidate)
                .toList();
    }

    private Candidate catalogCandidate(LhAnnouncementCatalogSourceView source) {
        LhAnnouncementRequest request = LhAnnouncementRequest.from(source);
        return new Candidate(CATALOG_SOURCE_PREFIX + source.getSourceKey(), "lhCatalogSource=" + source.getSourceKey(),
                request, source.getChangedAt(), source.getCollectedAt());
    }

    public Resolution resolve(MyHomeAnnouncementSourceView source) {
        return resolveAll(List.of(source)).getFirst();
    }

    public List<Resolution> resolveAll(List<MyHomeAnnouncementSourceView> sources) {
        List<Resolution> resolutions = sources.stream().map(this::infer).toList();
        List<String> panIds = resolutions.stream()
                .filter(Candidate.class::isInstance)
                .map(Candidate.class::cast)
                .map(Candidate::panId)
                .distinct()
                .toList();
        if (panIds.isEmpty()) {
            return resolutions;
        }
        Map<String, List<LhAnnouncementCatalogSourceView>> catalogByPanId = catalogRepository
                .findAllByPanIdInAndPresentInLatestCatalogTrue(panIds)
                .stream().collect(Collectors.groupingBy(LhAnnouncementCatalogSourceView::getPanId));
        return resolutions.stream().map(resolution -> {
            if (resolution instanceof Candidate candidate) {
                return (Resolution) resolveFromCatalog(
                        candidate, catalogByPanId.getOrDefault(candidate.panId(), List.of()));
            }
            return resolution;
        }).toList();
    }

    private Resolution infer(MyHomeAnnouncementSourceView source) {
        String sourceAnnouncementKey = sourceAnnouncementKey(source);
        String sourceDescription = sourceDescription(source);
        if (!isLhProvider(source)) {
            return new Skipped(sourceAnnouncementKey, sourceDescription, NON_LH_PROVIDER_REASON);
        }
        Optional<LhAnnouncementRequest> request = requestOf(source);
        if (request.isEmpty()) {
            return new Skipped(sourceAnnouncementKey, sourceDescription, UNSUPPORTED_REQUEST_REASON);
        }
        return new Candidate(sourceAnnouncementKey, sourceDescription, request.orElseThrow());
    }

    private Candidate resolveFromCatalog(Candidate candidate, List<LhAnnouncementCatalogSourceView> catalog) {
        List<LhAnnouncementCatalogSourceView> matches = catalog.stream()
                .filter(row -> matches(candidate.request(), row))
                .toList();
        if (matches.size() != 1) {
            return candidate;
        }
        LhAnnouncementCatalogSourceView row = matches.getFirst();
        LhAnnouncementRequest canonical = LhAnnouncementRequest.from(row);
        return new Candidate(candidate.sourceAnnouncementKey(), candidate.sourceDescription(), canonical,
                row.getChangedAt(), row.getCollectedAt());
    }

    private boolean matches(LhAnnouncementRequest request, LhAnnouncementCatalogSourceView row) {
        return request.panId().equals(row.getPanId())
                && request.connectionSystemDivisionCode().equals(row.getConnectionSystemDivisionCode())
                && request.upperAnnouncementTypeCode().equals(row.getUpperAnnouncementTypeCode())
                && (request.announcementTypeCode() == null
                || request.announcementTypeCode().equals(row.getAnnouncementTypeCode()));
    }

    private boolean isLhProvider(MyHomeAnnouncementSourceView source) {
        return LhProviderPolicy.isLh(source.getSuplyInsttNm());
    }

    private Optional<LhAnnouncementRequest> requestOf(MyHomeAnnouncementSourceView source) {
        Optional<String> supplyTypeCode = supplyTypeCodeOf(source.getSuplyTyNm());
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

    private String sourceDescription(MyHomeAnnouncementSourceView source) {
        if (source.getId() == null) {
            return source.getSourceKey();
        }
        return "myhomeAnnouncementSourceId=" + source.getId();
    }

    private Optional<String> supplyTypeCodeOf(String supplyTypeName) {
        if (supplyTypeName == null || supplyTypeName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(SUPPLY_TYPE_CODE_BY_NAME.get(supplyTypeName.strip()));
    }

    private String sourceAnnouncementKey(MyHomeAnnouncementSourceView source) {
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
