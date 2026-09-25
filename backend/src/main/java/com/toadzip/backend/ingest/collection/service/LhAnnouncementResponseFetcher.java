package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementDetailResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementSupplyResponseParser;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LhAnnouncementResponseFetcher {

    private final LhAnnouncementExternalRepository externalRepository;
    private final LhAnnouncementDetailResponseParser detailResponseParser;
    private final LhAnnouncementSupplyResponseParser supplyResponseParser;
    private final ExternalDataRetryExecutor retryExecutor;

    public LhAnnouncementResponseFetcher(
            LhAnnouncementExternalRepository externalRepository,
            LhAnnouncementDetailResponseParser detailResponseParser,
            LhAnnouncementSupplyResponseParser supplyResponseParser,
            ExternalDataRetryExecutor retryExecutor
    ) {
        this.externalRepository = externalRepository;
        this.detailResponseParser = detailResponseParser;
        this.supplyResponseParser = supplyResponseParser;
        this.retryExecutor = retryExecutor;
    }

    public List<LhAnnouncementDetailSource> fetchDetails(
            LhAnnouncementRequest request,
            ExternalDataCallCounter callCounter
    ) {
        return fetch(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                request,
                callCounter,
                externalRepository::fetchDetail,
                detailResponseParser::parse
        );
    }

    public List<LhAnnouncementSupplySource> fetchSupplies(
            LhAnnouncementRequest request,
            ExternalDataCallCounter callCounter
    ) {
        return fetch(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                request,
                callCounter,
                externalRepository::fetchSupply,
                (panId, root) -> supplyResponseParser.parse(
                        panId, request.supplyInfoTypeCode(), root
                )
        );
    }

    private <T> List<T> fetch(
            ExternalDataSource source,
            LhAnnouncementRequest request,
            ExternalDataCallCounter callCounter,
            Function<LhAnnouncementRequest, ExternalDataResponse> externalFetch,
            ResponseParser<T> parser
    ) {
        return retryExecutor.execute(
                source,
                request.requestDescription(),
                () -> parser.parse(request.panId(), externalFetch.apply(request).body()),
                callCounter
        );
    }

    @FunctionalInterface
    private interface ResponseParser<T> {

        List<T> parse(String panId, JsonNode root);
    }

}
