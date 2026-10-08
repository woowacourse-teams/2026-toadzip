package com.toadzip.backend.ingest.source.service;

import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.ingest.source.dto.IngestSourcePageResponse;
import com.toadzip.backend.ingest.source.repository.IngestSourceQueryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class IngestSourceQueryService {

    private final IngestSourceQueryRepository repository;
    private final String myHomeComplexBaseUrl;
    private final String myHomeAnnouncementBaseUrl;
    private final String lhBaseUrl;

    public IngestSourceQueryService(
            IngestSourceQueryRepository repository,
            @Value("${ingest.base-url.myhome-complex}") String myHomeComplexBaseUrl,
            @Value("${ingest.base-url.myhome-announcement}") String myHomeAnnouncementBaseUrl,
            @Value("${ingest.base-url.lh}") String lhBaseUrl
    ) {
        this.repository = repository;
        this.myHomeComplexBaseUrl = myHomeComplexBaseUrl;
        this.myHomeAnnouncementBaseUrl = myHomeAnnouncementBaseUrl;
        this.lhBaseUrl = lhBaseUrl;
    }

    @Transactional(readOnly = true)
    public IngestSourcePageResponse findSources(IngestSourceCategory category, int page, int size, String keyword) {
        return repository.findSources(category, page, size, keyword, sourceUrl(category));
    }

    private String sourceUrl(IngestSourceCategory category) {
        return switch (category) {
            case MYHOME_COMPLEX -> endpoint(myHomeComplexBaseUrl, "rentalHouseGwList");
            case LH_LEASE_CATALOG -> endpoint(lhBaseUrl, "lhLeaseInfo1/lhLeaseInfo1");
            case MYHOME_ANNOUNCEMENT -> endpoint(myHomeAnnouncementBaseUrl, "rsdtRcritNtcList");
            case LH_ANNOUNCEMENT_CATALOG -> endpoint(lhBaseUrl, "lhLeaseNoticeInfo1/lhLeaseNoticeInfo1");
            case LH_ANNOUNCEMENT_DETAIL -> endpoint(lhBaseUrl, "lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1");
            case LH_ANNOUNCEMENT_SUPPLY -> endpoint(lhBaseUrl, "lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1");
        };
    }

    private String endpoint(String baseUrl, String path) {
        String publicBaseUrl = UriComponentsBuilder.fromUriString(baseUrl)
                .userInfo(null).replaceQuery(null).fragment(null).build().toUriString();
        return publicBaseUrl.replaceAll("/+$", "") + "/" + path;
    }
}
