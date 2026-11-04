package com.toadzip.backend.ingest.sh.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.board.service.BoardSourceCollector;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementExternalRepository;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementListParser;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementHtmlParser;
import com.toadzip.backend.ingest.collection.service.CollectionServiceTestFixture;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineStoppedException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(IngestExecutionExtension.class)
@ExtendWith(MockitoExtension.class)
class ShAnnouncementCollectionServiceTest {

    private static final Instant RECENTLY_COLLECTED = Instant.parse("2026-10-04T00:00:00Z");

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
    void 매입임대와_전세임대_게시글은_상세를_받지_않는다() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 3, 100, 101, 102)
                .replace("공고 101</a>", "청년 매입임대주택 입주자 모집</a>")
                .replace("공고 102</a>", "기존주택 전세임대 입주자 모집</a>"));
        when(externalRepository.fetchDetail("100")).thenReturn(detail("100"));

        var report = service.collect();

        assertThat(report.storedRowCount()).isOne();
        verify(externalRepository, never()).fetchDetail("101");
        verify(externalRepository, never()).fetchDetail("102");
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
    void 제목과_등록일이_그대로인_수집된_게시글은_상세를_다시_받지_않는다() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 2, 100, 101));
        when(store.findUnchangedCollectedAt(any())).thenReturn(Map.of("100", RECENTLY_COLLECTED));
        when(externalRepository.fetchDetail("101")).thenReturn(detail("101"));

        var report = service.collect();

        verify(externalRepository, never()).fetchDetail("100");
        assertThat(report.skippedRequestCount()).isOne();
        assertThat(report.storedRowCount()).isOne();
        assertThat(report.externalApiCallCount()).isEqualTo(2);
        verify(failures, never()).resolve(ExternalDataSource.SH_ANNOUNCEMENT, "seq=100");
    }

    @Test
    void 실패가_열려_있는_게시글은_제목이_같아도_다시_받는다() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 1, 100));
        when(store.findUnchangedCollectedAt(any())).thenReturn(Map.of("100", RECENTLY_COLLECTED));
        when(failures.findPendingRequestDescriptions(ExternalDataSource.SH_ANNOUNCEMENT, List.of("seq=100")))
                .thenReturn(Set.of("seq=100"));
        when(externalRepository.fetchDetail("100")).thenReturn(detail("100"));

        var report = service.collect();

        assertThat(report.skippedRequestCount()).isZero();
        assertThat(report.storedRowCount()).isOne();
        verify(failures).resolve(ExternalDataSource.SH_ANNOUNCEMENT, "seq=100");
    }

    @Test
    void 첨부를_읽지_못한_상세는_읽은_내용만_저장하고_실패를_열어_둔다() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 1, 100));
        when(externalRepository.fetchDetail("100")).thenReturn(detail("100")
                .replace("<script>initParam.downList = [];</script>", "")
                .replace("<ul class='personInfo'><li><span>담당부서</span>공급부</li></ul>", ""));

        var report = service.collect();

        assertThat(report.storedRowCount()).isOne();
        assertThat(report.failedRequestCount()).isOne();
        verify(store).store(org.mockito.ArgumentMatchers.argThat(snapshot -> snapshot.seq().equals("100")
                && snapshot.bodyText().equals("공고 100 원문") && snapshot.attachments() == null
                && snapshot.department().equals("목록부")));
        verify(failures).record(eq(ExternalDataSource.SH_ANNOUNCEMENT), eq("seq=100"),
                org.mockito.ArgumentMatchers.argThat(exception -> exception.getMessage().contains("첨부")), any(), any());
        verify(failures, never()).resolve(ExternalDataSource.SH_ANNOUNCEMENT, "seq=100");
    }

    @Test
    void rateLimitStopsRemainingRequestsWithoutRetrying() {
        when(externalRepository.fetchList(1)).thenReturn(list(1, 11, 100, 101));
        when(externalRepository.fetchDetail("100")).thenThrow(ExternalDataRequestException.rateLimited("429"));

        var report = service.collect();

        assertThat(report.rateLimitedRequestCount()).isOne();
        assertThat(report.externalApiCallCount()).isEqualTo(2);
        verify(externalRepository, never()).fetchDetail("101");
        verify(externalRepository, never()).fetchList(2);
        verify(store, never()).store(any());
    }

    @Test
    void 요청이_연속으로_실패하면_남은_글의_상세를_요청하지_않는다() {
        Integer[] ids = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109};
        when(externalRepository.fetchList(1)).thenReturn(list(1, 10, ids));
        for (int id = 100; id <= 104; id++) {
            when(externalRepository.fetchDetail(Integer.toString(id))).thenThrow(ExternalDataRequestException.retryable("503"));
        }

        var report = service.collect();

        assertThat(report.failedRequestCount()).isEqualTo(5);
        verify(externalRepository, never()).fetchDetail("105");
        verify(store, never()).store(any());
    }

    @Test
    void 실패가_이어져도_중간에_성공하면_수집을_멈추지_않는다() {
        Integer[] ids = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109};
        when(externalRepository.fetchList(1)).thenReturn(list(1, 10, ids));
        for (int id : ids) {
            String seq = Integer.toString(id);
            if (id == 104 || id == 109) {
                when(externalRepository.fetchDetail(seq)).thenReturn(detail(seq));
            }
            else {
                when(externalRepository.fetchDetail(seq)).thenThrow(new ExternalDataRequestException("HTTP 404"));
            }
        }

        var report = service.collect();

        assertThat(report.storedRowCount()).isEqualTo(2);
        assertThat(report.failedRequestCount()).isEqualTo(8);
        verify(externalRepository).fetchDetail("109");
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
    void 받지_않는_매입임대_글이_페이지_사이에_겹쳐도_뒤_페이지를_실패로_기록한다() {
        String purchase = "청년 매입임대주택 입주자 모집";
        when(externalRepository.fetchList(1)).thenReturn(list(1, 11, 100).replace("공고 101</a>", purchase + "</a>"));
        when(externalRepository.fetchList(2)).thenReturn(list(2, 11, 110)
                .replace("getDetailView('110')\">공고 110", "getDetailView('101')\">" + purchase));
        when(externalRepository.fetchDetail("100")).thenReturn(detail("100"));

        service.collect();

        verify(failures).record(eq(ExternalDataSource.SH_ANNOUNCEMENT), eq("page=2"), any(), any(), any());
        verify(externalRepository, never()).fetchDetail("101");
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
        return new ShAnnouncementCollectionService(
                new ShAnnouncementBoardSource(externalRepository, new ShAnnouncementListParser(), detailParser, store),
                new BoardSourceCollector(CollectionServiceTestFixture.retryWithoutDelay(), failures,
                        Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC), Duration.ofDays(7)),
                days, Duration.ZERO);
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
            rows.append("<td>목록부</td><td>" + date + "</td><td>100</td></tr>");
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
