package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeRegion;
import com.toadzip.backend.ingest.collection.repository.MyHomeComplexExternalRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeComplexResponseParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MyHomeComplexRegionCollector {

    private final MyHomeComplexResponseParser responseParser;
    private final MyHomeComplexExternalRepository externalRepository;
    private final MyHomeSourceStore sourceStore;
    private final ExternalDataFailureRecorder failureRecorder;
    private final ExternalDataRetryExecutor retryExecutor;

    public MyHomeComplexCollectionReport collect(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request
    ) {
        return collect(region, request, new AtomicBoolean());
    }

    public MyHomeComplexCollectionReport collect(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request,
            AtomicBoolean rateLimitReached
    ) {
        if (rateLimitReached.get()) {
            return MyHomeComplexCollectionReport.empty();
        }
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        FetchedRegion fetchedRegion;
        try {
            fetchedRegion = fetchCompleteRegion(region, request, callCounter, rateLimitReached);
        }
        catch (RateLimitCollectionCancelledException exception) {
            return cancelledReport(callCounter);
        }
        catch (ExternalDataRetryInterruptedException exception) {
            if (rateLimitReached.get()) {
                return cancelledReport(callCounter);
            }
            throw exception;
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            return failedReport(region, request, rateLimitReached, callCounter, exception);
        }
        int storedRowCount = sourceStore.replaceComplexRegion(region, fetchedRegion.snapshots());
        resolveFailures(fetchedRegion.requestDescriptions());
        return new MyHomeComplexCollectionReport(
                ExternalDataSource.MYHOME_COMPLEX.operation(),
                storedRowCount,
                0,
                callCounter.count()
        );
    }

    private MyHomeComplexCollectionReport cancelledReport(ExternalDataCallCounter callCounter) {
        return new MyHomeComplexCollectionReport(
                ExternalDataSource.MYHOME_COMPLEX.operation(),
                0,
                0,
                callCounter.count()
        );
    }

    private MyHomeComplexCollectionReport failedReport(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request,
            AtomicBoolean rateLimitReached,
            ExternalDataCallCounter callCounter,
            RuntimeException exception
    ) {
        int rateLimitedRequestCount = ExternalDataRateLimit.count(exception);
        if (rateLimitedRequestCount > 0) {
            rateLimitReached.set(true);
        }
        failureRecorder.record(
                ExternalDataSource.MYHOME_COMPLEX,
                request.requestDescription(region, 1),
                exception,
                log,
                "마이홈 단지 지역 수집에 실패했습니다"
        );
        return new MyHomeComplexCollectionReport(
                ExternalDataSource.MYHOME_COMPLEX.operation(),
                0,
                1,
                callCounter.count(),
                rateLimitedRequestCount
        );
    }

    private FetchedRegion fetchCompleteRegion(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request,
            ExternalDataCallCounter callCounter,
            AtomicBoolean rateLimitReached
    ) {
        List<MyHomeComplexSourceSnapshot> snapshots = new ArrayList<>();
        List<String> requestDescriptions = new ArrayList<>();
        int expectedTotalCount = -1;
        for (int page = 1; page <= request.maxPages(); page++) {
            if (rateLimitReached.get()) {
                throw new RateLimitCollectionCancelledException();
            }
            int currentPage = page;
            int expectedTotalCountForPage = expectedTotalCount;
            String requestDescription = request.requestDescription(region, currentPage);
            ExternalDataPage<MyHomeComplexSourceSnapshot> parsedPage = retryExecutor.execute(
                    ExternalDataSource.MYHOME_COMPLEX,
                    requestDescription,
                    () -> parsePage(region, request, currentPage, snapshots.size(), expectedTotalCountForPage),
                    callCounter
            );
            expectedTotalCount = parsedPage.totalCount();
            snapshots.addAll(parsedPage.items());
            requestDescriptions.add(requestDescription);
            if (parsedPage.completesCollection(snapshots.size(), request.pageSize())) {
                return new FetchedRegion(snapshots, requestDescriptions);
            }
        }
        throw new ExternalDataRequestException("마이홈 단지 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }

    private ExternalDataPage<MyHomeComplexSourceSnapshot> parsePage(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request,
            int page,
            int collectedCount,
            int expectedTotalCount
    ) {
        ExternalDataPage<MyHomeComplexSourceSnapshot> parsedPage = responseParser.parseItems(
                responseParser.validate(externalRepository.fetch(region, request, page), collectedCount)
        );
        if (expectedTotalCount >= 0 && expectedTotalCount != parsedPage.totalCount()) {
            throw new ExternalDataRequestException("마이홈 단지 응답의 totalCount가 페이지마다 다릅니다.");
        }
        return parsedPage;
    }

    private void resolveFailures(List<String> requestDescriptions) {
        requestDescriptions.forEach(requestDescription -> failureRecorder.resolve(
                ExternalDataSource.MYHOME_COMPLEX,
                requestDescription
        ));
    }

    private static final class RateLimitCollectionCancelledException extends RuntimeException {
    }

    private record FetchedRegion(
            List<MyHomeComplexSourceSnapshot> snapshots,
            List<String> requestDescriptions
    ) {
    }
}
