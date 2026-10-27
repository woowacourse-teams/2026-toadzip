package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.ShAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.ShAnnouncementStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.ShAnnouncementListParser;
import com.toadzip.backend.ingest.collection.repository.external.ShAnnouncementHtmlParser;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineStoppedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShAnnouncementCollectionServiceTest {

    private final ShAnnouncementHtmlParser detailParser =
            new ShAnnouncementHtmlParser();

    @Mock
    private ShAnnouncementExternalRepository externalRepository;
    @Mock
    private ShAnnouncementStore store;
    @Mock
    private ExternalDataFailureRecorder failures;

    private ShAnnouncementCollectionService service;

    @BeforeEach
    void setUp() {
        service = service(90);
    }

    @Test
    void doesNotOverwriteStoredSourceWhenRequiredDetailRegionIsMissing() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 1, 100));
        when(externalRepository.fetchDetail("100")).thenReturn("<html>오류 페이지</html>");
        var report = service.collect();
        assertThat(report.failedRequestCount()).isOne();
        assertThat(report.storedRowCount()).isZero();
        verify(store, never()).store(any());
    }

    @Test
    void 상세_본문과_첨부를_정제해_저장한다() {
        String listHtml = list(1, 1, 100);
        String detailHtml = detail("100");
        when(externalRepository.fetchList(1)).thenReturn(listHtml);
        when(externalRepository.fetchDetail("100")).thenReturn(detailHtml);

        var report = service.collect();

        assertThat(report.storedRowCount()).isOne();
        assertThat(report.failedRequestCount()).isZero();
        verify(store).store(org.mockito.ArgumentMatchers.argThat(snapshot ->
                snapshot.seq().equals("100") && snapshot.title().equals("공고 100")
                        && snapshot.bodyText().equals("공고 100 원문") && snapshot.attachments().equals("[]")));
    }

    @Test
    void savesSelectedPostsOnEveryPageAndResolvesTheirFailures() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 11, 100));
        when(externalRepository.fetchList(2)).thenReturn(list(2, 11, 110));
        when(externalRepository.fetchDetail("100")).thenReturn(detail("100"));
        when(externalRepository.fetchDetail("110")).thenReturn(detail("110"));

        var report = service.collect();

        assertThat(report.storedRowCount()).isEqualTo(2);
        assertThat(report.externalApiCallCount()).isEqualTo(4);
        assertThat(report.successfulRequestCount()).isEqualTo(4);
        assertThat(report.failedRequestCount()).isZero();
        verify(failures).resolve(ExternalDataSource.SH_ANNOUNCEMENT, "seq=100");
        verify(failures).resolve(ExternalDataSource.SH_ANNOUNCEMENT, "page=2");
    }

    @Test
    void laterListFailureKeepsAlreadyStoredPostsAndRecordsFailedPage() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 11, 100));
        when(externalRepository.fetchDetail("100")).thenReturn(detail("100"));
        when(externalRepository.fetchList(2)).thenThrow(ExternalDataRequestException.retryable("503"));

        var report = service.collect();

        assertThat(report.storedRowCount()).isOne();
        assertThat(report.failedRequestCount()).isOne();
        assertThat(report.externalApiCallCount()).isEqualTo(5);
        verify(failures).record(eq(ExternalDataSource.SH_ANNOUNCEMENT), eq("page=2"), any(), any(), any());
    }

    @Test
    void failedDetailDoesNotReplaceSourceAndNextPostStillSucceeds() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 3, 100, 101, 102));
        when(externalRepository.fetchDetail("100")).thenReturn(detail("100"));
        when(externalRepository.fetchDetail("101"))
                .thenThrow(new ExternalDataRequestException("SH 요청 실패: HTTP 404"));
        when(externalRepository.fetchDetail("102")).thenReturn(detail("102"));

        var report = service.collect();

        assertThat(report.storedRowCount()).isEqualTo(2);
        assertThat(report.failedRequestCount()).isOne();
        verify(store, never()).store(org.mockito.ArgumentMatchers.argThat(snapshot -> snapshot.seq().equals("101")));
        verify(failures).record(eq(ExternalDataSource.SH_ANNOUNCEMENT), eq("seq=101"), any(), any(), any());
    }

    @Test
    void rateLimitStopsRemainingRequestsWithoutRetrying() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 2, 100, 101));
        when(externalRepository.fetchDetail("100")).thenThrow(ExternalDataRequestException.rateLimited("429"));

        var report = service.collect();

        assertThat(report.rateLimitedRequestCount()).isOne();
        assertThat(report.externalApiCallCount()).isEqualTo(2);
        verify(externalRepository, never()).fetchDetail("101");
        verify(store, never()).store(any());
    }

    @Test
    void cutoffIsInclusiveAndOldListPageDoesNotHideRecentPostOnLaterPage() {
        String first = list(1, 11).replace("2026-01-01", "2026-07-06");
        when(externalRepository.fetchList(1)).thenReturn(first);
        when(externalRepository.fetchList(2)).thenReturn(list(2, 11, 110).replace("2026-10-02", "2026-07-07"));
        when(externalRepository.fetchDetail("110")).thenReturn(detail("110").replace("2026-10-02", "2026-07-07"));

        var report = service.collect();

        assertThat(report.storedRowCount()).isOne();
        assertThat(report.externalApiCallCount()).isEqualTo(3);
        verify(externalRepository, never()).fetchDetail("100");
    }

    @Test
    void fullHistoryOptionIncludesOldPosts() {
        service = service(0);
        when(externalRepository.fetchList(1)).thenReturn(list(1, 1));
        when(externalRepository.fetchDetail("100")).thenReturn(detail("100").replace("2026-10-02", "2026-01-01"));

        assertThat(service.collect().storedRowCount()).isOne();
    }

    @Test
    void emptyListMakesNoDetailRequests() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 0));
        assertThat(service.collect().externalApiCallCount()).isOne();
        verify(store, never()).store(any());
    }

    @Test
    void changedTotalStopsBeforeCollectingNextPage() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 11));
        when(externalRepository.fetchList(2)).thenReturn(list(2, 12, 110));

        assertThat(service.collect().failedRequestCount()).isOne();
        verify(externalRepository, never()).fetchDetail("110");
    }

    @Test
    void stopDuringStorePropagatesInsteadOfBecomingCollectionFailure() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 1, 100));
        when(externalRepository.fetchDetail("100")).thenReturn(detail("100"));
        doThrow(new DataPipelineStoppedException()).when(store).store(any());

        assertThatThrownBy(service::collect).isInstanceOf(DataPipelineStoppedException.class);
        verify(failures, never()).record(any(), any(), any(), any(), any());
    }

    private ShAnnouncementCollectionService service(int days) {
        var metrics = new SimpleMeterRegistry();
        return new ShAnnouncementCollectionService(externalRepository, new ShAnnouncementListParser(), detailParser, store,
                new ExternalDataRetryExecutor(Duration.ZERO, metrics), failures,
                Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC), days, Duration.ZERO);
    }

    private String list(int page, int total, Integer... recentIds) {
        StringBuilder rows = new StringBuilder();
        int start = (page - 1) * 10;
        for (int index = start; index < Math.min(start + 10, total); index++) {
            int seq = 100 + index;
            String date = "2026-01-01";
            if (List.of(recentIds).contains(seq)) {
                date = "2026-10-02";
            }
            rows.append("<tr><td>123</td><td class='txtL'><a onclick=\"getDetailView('%d')\">공고 %d</a></td>"
                    .formatted(seq, seq));
            rows.append("<td>공급부</td><td>" + date + "</td><td>100</td></tr>");
        }
        return "<input name='multi_itm_seq' value='2'><div class='topTxt'><p>총 <strong>%d</strong> 건 [%d/%d페이지]"
                .formatted(total, page, Math.max(1, (total + 9) / 10))
                + "</p></div><div id='listTb'><table><tbody>" + rows + "</tbody></table></div>";
    }

    private String detail(String seq) {
        return "<html><nav>메뉴</nav><input name='multi_itm_seq' value='2'>"
                + "<div class='detailTable gs0401Table firgs0401Table'><table><thead><tr><th>공고 "
                + seq + "</th></tr></thead><tbody><tr><td><ul><li><strong>등록일:</strong>2026-10-02</li></ul></td></tr>"
                + "<tr><td class='cont'><p>공고 " + seq + " 원문</p></td></tr></tbody></table></div>"
                + "<ul class='personInfo'><li><span>담당부서</span>공급부</li></ul>"
                + "<script>initParam.downList = [];</script><footer>푸터</footer></html>";
    }
}
