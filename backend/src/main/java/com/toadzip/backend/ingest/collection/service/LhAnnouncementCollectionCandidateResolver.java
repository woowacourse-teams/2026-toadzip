package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import java.net.URI;
import java.util.Optional;
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

    public Resolution resolve(MyHomeAnnouncementSource source) {
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
            LhAnnouncementRequest request
    ) implements Resolution {

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
