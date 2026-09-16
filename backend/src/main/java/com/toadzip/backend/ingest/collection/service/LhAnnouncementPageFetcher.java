package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementResponsePage;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementDetailResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementSupplyResponseParser;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LhAnnouncementPageFetcher {

    private static final int MAX_PAGES = 10_000;

    private final LhAnnouncementExternalRepository externalRepository;
    private final LhAnnouncementDetailResponseParser detailResponseParser;
    private final LhAnnouncementSupplyResponseParser supplyResponseParser;
    private final ExternalDataRetryExecutor retryExecutor;

    public LhAnnouncementPageFetcher(
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

    public FetchedPages<LhAnnouncementDetailSource> fetchDetails(
            LhAnnouncementRequest request,
            ExternalDataCallCounter callCounter
    ) {
        return fetchAll(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                request,
                callCounter,
                externalRepository::fetchDetail,
                detailResponseParser::parsePage
        );
    }

    public FetchedPages<LhAnnouncementSupplySource> fetchSupplies(
            LhAnnouncementRequest request,
            ExternalDataCallCounter callCounter
    ) {
        return fetchAll(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                request,
                callCounter,
                externalRepository::fetchSupply,
                supplyResponseParser::parsePage
        );
    }

    private <T> FetchedPages<T> fetchAll(
            ExternalDataSource source,
            LhAnnouncementRequest request,
            ExternalDataCallCounter callCounter,
            Function<LhAnnouncementRequest, ExternalDataResponse> fetch,
            PageParser<T> parser
    ) {
        List<T> items = new ArrayList<>();
        List<String> requestDescriptions = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            LhAnnouncementRequest pageRequest = request.withPage(page);
            LhAnnouncementResponsePage<T> responsePage = fetchPage(
                    source,
                    pageRequest,
                    items.size(),
                    callCounter,
                    fetch,
                    parser
            );
            items.addAll(responsePage.items());
            requestDescriptions.add(pageRequest.pageRequestDescription());
            if (responsePage.maximumDatasetRowCount() < pageRequest.pageSize()) {
                return new FetchedPages<>(items, requestDescriptions);
            }
        }
        throw maximumPagesExceeded(source, request);
    }

    private <T> LhAnnouncementResponsePage<T> fetchPage(
            ExternalDataSource source,
            LhAnnouncementRequest request,
            int sourceOrderOffset,
            ExternalDataCallCounter callCounter,
            Function<LhAnnouncementRequest, ExternalDataResponse> fetch,
            PageParser<T> parser
    ) {
        return retryExecutor.execute(
                source,
                request.pageRequestDescription(),
                () -> parsePage(request, sourceOrderOffset, fetch, parser),
                callCounter
        );
    }

    private <T> LhAnnouncementResponsePage<T> parsePage(
            LhAnnouncementRequest request,
            int sourceOrderOffset,
            Function<LhAnnouncementRequest, ExternalDataResponse> fetch,
            PageParser<T> parser
    ) {
        ExternalDataResponse response = fetch.apply(request);
        LhAnnouncementResponsePage<T> page = parser.parse(request.panId(), response.body(), sourceOrderOffset);
        if (page.maximumDatasetRowCount() > request.pageSize()) {
            throw new ExternalDataRequestException("LH 공고 응답 행 수가 요청한 페이지 크기를 초과했습니다.");
        }
        return page;
    }

    private ExternalDataCallFailureException maximumPagesExceeded(
            ExternalDataSource source,
            LhAnnouncementRequest request
    ) {
        LhAnnouncementRequest lastPageRequest = request.withPage(MAX_PAGES);
        ExternalDataRequestException cause = new ExternalDataRequestException(
                "LH 공고 조회가 최대 페이지 안에 끝나지 않았습니다."
        );
        return new ExternalDataCallFailureException(
                source,
                lastPageRequest.pageRequestDescription(),
                1,
                cause
        );
    }

    @FunctionalInterface
    private interface PageParser<T> {

        LhAnnouncementResponsePage<T> parse(String panId, JsonNode root, int sourceOrderOffset);
    }

    public record FetchedPages<T>(List<T> items, List<String> requestDescriptions) {

        public FetchedPages {
            items = List.copyOf(items);
            requestDescriptions = List.copyOf(requestDescriptions);
        }
    }
}
