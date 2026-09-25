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
import tools.jackson.databind.node.ObjectNode;

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
                (panId, root, sourceOrderOffset) -> supplyResponseParser.parsePage(
                        panId, request.supplyInfoTypeCode(), root, sourceOrderOffset
                )
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
        JsonNode previousPageContent = null;
        for (int page = 1; page <= MAX_PAGES; page++) {
            LhAnnouncementRequest pageRequest = request.withPage(page);
            FetchedResponsePage<T> fetchedPage = fetchPage(
                    source,
                    pageRequest,
                    items.size(),
                    callCounter,
                    fetch,
                    parser
            );
            LhAnnouncementResponsePage<T> responsePage = fetchedPage.page();
            if (repeatsFullPage(previousPageContent, fetchedPage, pageRequest.pageSize())) {
                throw repeatedPage(source, pageRequest);
            }
            items.addAll(responsePage.items());
            requestDescriptions.add(pageRequest.pageRequestDescription());
            if (responsePage.maximumDatasetRowCount() < pageRequest.pageSize()) {
                return new FetchedPages<>(items, requestDescriptions);
            }
            previousPageContent = fetchedPage.pageContent();
        }
        throw maximumPagesExceeded(source, request);
    }

    private <T> FetchedResponsePage<T> fetchPage(
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

    private <T> FetchedResponsePage<T> parsePage(
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
        return new FetchedResponsePage<>(page, pageContentOf(response.body()));
    }

    private <T> boolean repeatsFullPage(
            JsonNode previousPageContent,
            FetchedResponsePage<T> currentPage,
            int pageSize
    ) {
        return currentPage.page().maximumDatasetRowCount() == pageSize
                && currentPage.pageContent().equals(previousPageContent);
    }

    private JsonNode pageContentOf(JsonNode responseBody) {
        JsonNode pageContent = responseBody.deepCopy();
        removeResponseHeaders(pageContent);
        return pageContent;
    }

    private void removeResponseHeaders(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.remove("resHeader");
        }
        node.forEach(this::removeResponseHeaders);
    }

    private ExternalDataCallFailureException repeatedPage(
            ExternalDataSource source,
            LhAnnouncementRequest request
    ) {
        ExternalDataRequestException cause = new ExternalDataRequestException(
                "LH 공고 API가 동일한 페이지를 반복 응답했습니다."
        );
        return new ExternalDataCallFailureException(
                source,
                request.pageRequestDescription(),
                1,
                cause
        );
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

    private record FetchedResponsePage<T>(
            LhAnnouncementResponsePage<T> page,
            JsonNode pageContent
    ) {
    }

    public record FetchedPages<T>(List<T> items, List<String> requestDescriptions) {

        public FetchedPages {
            items = List.copyOf(items);
            requestDescriptions = List.copyOf(requestDescriptions);
        }
    }
}
