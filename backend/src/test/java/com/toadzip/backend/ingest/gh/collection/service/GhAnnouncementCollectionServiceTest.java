package com.toadzip.backend.ingest.gh.collection.service;

import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.PURCHASE_RENTAL;
import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.RENTAL_HOUSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementHtmlParser;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementListParser;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementStore;
import com.toadzip.backend.ingest.gh.collection.repository.GhHousingTypeDetails;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.CollectionServiceTestFixture;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineStoppedException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(IngestExecutionExtension.class)
@ExtendWith(MockitoExtension.class)
class GhAnnouncementCollectionServiceTest {

    private static final String RECENT = "2026-10-02";
    private static final String OLD = "2026-01-01";

    @Mock
    private GhAnnouncementExternalRepository externalRepository;
    @Mock
    private GhAnnouncementStore store;
    @Mock
    private ExternalDataFailureRecorder failures;

    private GhAnnouncementCollectionService service;

    @BeforeEach
    void setUp() {
        service = service(90);
    }

    @Test
    void 두_게시판의_최근_공고를_상세까지_읽어_게시판과_함께_저장한다() {
        Row rental = row(801, RECENT);
        Row purchase = row(795, RECENT);
        list(RENTAL_HOUSE, 1, 1, rental);
        list(PURCHASE_RENTAL, 1, 1, purchase);
        detail(RENTAL_HOUSE, rental);
        detail(PURCHASE_RENTAL, purchase);

        var report = service.collect();

        assertThat(report.storedRowCount()).isEqualTo(2);
        // 목록 2 + 상세 2 + 주택형 팝업(상세마다 2)
        assertThat(report.externalApiCallCount()).isEqualTo(8);
        assertThat(report.successfulRequestCount()).isEqualTo(4);
        assertThat(report.failedRequestCount()).isZero();
        verify(store).store(argThat(snapshot -> snapshot.board() == RENTAL_HOUSE && snapshot.pbancNo().equals("801")
                && snapshot.region().equals("남양주시") && snapshot.status().equals("접수마감")
                && snapshot.closingDate().equals(LocalDate.parse("2026-10-20"))
                && snapshot.originalUrl().equals(RENTAL_HOUSE.detailUrl("801", "01"))
                && snapshot.listUrl().equals(RENTAL_HOUSE.listUrl(1))
                && snapshot.districts().contains("\"supplyCount\":\"120\"") && snapshot.reception().contains("다산 임대센터")
                && snapshot.notice().equals("※ 첫 줄\n※ 둘째 줄") && snapshot.attachments().contains("모집공고문.pdf")));
        verify(store).store(argThat(snapshot -> snapshot.board() == PURCHASE_RENTAL
                && snapshot.pbancNo().equals("795")));
        verify(failures).resolve(ExternalDataSource.GH_ANNOUNCEMENT, "board=sr7155&page=1");
        verify(failures).resolve(ExternalDataSource.GH_ANNOUNCEMENT, "pbancNo=801");
    }

    @Test
    void 모든_페이지를_넘기며_조회_기간_안의_공고만_상세를_요청한다() {
        Row[] first = IntStream.range(0, 10).mapToObj(index -> row(900 - index, index == 0 ? RECENT : OLD))
                .toArray(Row[]::new);
        Row laterRecent = row(800, "2026-07-07");
        list(RENTAL_HOUSE, 1, 11, first);
        list(RENTAL_HOUSE, 2, 11, laterRecent);
        list(PURCHASE_RENTAL, 1, 0);
        detail(RENTAL_HOUSE, first[0]);
        detail(RENTAL_HOUSE, laterRecent);

        var report = service.collect();

        assertThat(report.storedRowCount()).isEqualTo(2);
        assertThat(report.externalApiCallCount()).isEqualTo(9);
        verify(externalRepository, never()).fetchDetail(RENTAL_HOUSE, "899", "01");
    }

    @Test
    void 조회_기간을_0으로_두면_오래된_공고도_수집한다() {
        service = service(0);
        Row old = row(801, OLD);
        list(RENTAL_HOUSE, 1, 1, old);
        list(PURCHASE_RENTAL, 1, 0);
        detail(RENTAL_HOUSE, old);

        assertThat(service.collect().storedRowCount()).isOne();
    }

    @Test
    void 상세_구조가_바뀐_공고는_저장하지_않고_요청_단위로_기록한_뒤_다음_공고를_계속한다() {
        Row broken = row(802, RECENT);
        Row next = row(801, RECENT);
        list(RENTAL_HOUSE, 1, 2, broken, next);
        list(PURCHASE_RENTAL, 1, 0);
        when(externalRepository.fetchDetail(RENTAL_HOUSE, "802", "01")).thenReturn("<html>오류 페이지</html>");
        detail(RENTAL_HOUSE, next);

        var report = service.collect();

        assertThat(report.storedRowCount()).isOne();
        assertThat(report.failedRequestCount()).isOne();
        verify(store, never()).store(argThat(snapshot -> snapshot.pbancNo().equals("802")));
        verify(failures).record(eq(ExternalDataSource.GH_ANNOUNCEMENT), eq("pbancNo=802"), any(), any(), any());
    }

    @Test
    void 목록_실패는_그_게시판만_멈추고_다른_게시판은_계속_수집한다() {
        Row purchase = row(795, RECENT);
        when(externalRepository.fetchList(RENTAL_HOUSE, 1)).thenThrow(ExternalDataRequestException.retryable("503"));
        list(PURCHASE_RENTAL, 1, 1, purchase);
        detail(PURCHASE_RENTAL, purchase);

        var report = service.collect();

        assertThat(report.storedRowCount()).isOne();
        assertThat(report.failedRequestCount()).isOne();
        verify(failures).record(eq(ExternalDataSource.GH_ANNOUNCEMENT), eq("board=sr7150&page=1"), any(), any(), any());
    }

    @Test
    void 호출_제한에_걸리면_남은_요청과_다음_게시판을_멈춘다() {
        list(RENTAL_HOUSE, 1, 2, row(802, RECENT), row(801, RECENT));
        when(externalRepository.fetchDetail(RENTAL_HOUSE, "802", "01"))
                .thenThrow(ExternalDataRequestException.rateLimited("429"));

        var report = service.collect();

        assertThat(report.rateLimitedRequestCount()).isOne();
        assertThat(report.externalApiCallCount()).isEqualTo(2);
        verify(externalRepository, never()).fetchDetail(RENTAL_HOUSE, "801", "01");
        verify(externalRepository, never()).fetchList(eq(PURCHASE_RENTAL), any(Integer.class));
        verify(store, never()).store(any());
    }

    @Test
    void 수집_중_전체_건수가_바뀌면_다음_페이지_공고를_수집하지_않는다() {
        list(RENTAL_HOUSE, 1, 11, IntStream.range(0, 10).mapToObj(index -> row(900 - index, OLD)).toArray(Row[]::new));
        list(RENTAL_HOUSE, 2, 12, row(800, RECENT), row(799, RECENT));
        list(PURCHASE_RENTAL, 1, 0);

        assertThat(service.collect().failedRequestCount()).isOne();
        verify(externalRepository, never()).fetchDetail(RENTAL_HOUSE, "800", "01");
    }

    @Test
    void 두_게시판에_같은_공고_번호가_있으면_뒤_게시판_목록을_실패로_기록한다() {
        Row rental = row(801, RECENT);
        list(RENTAL_HOUSE, 1, 1, rental);
        list(PURCHASE_RENTAL, 1, 1, rental);
        detail(RENTAL_HOUSE, rental);

        var report = service.collect();

        assertThat(report.storedRowCount()).isOne();
        verify(failures).record(eq(ExternalDataSource.GH_ANNOUNCEMENT), eq("board=sr7155&page=1"), any(), any(), any());
        verify(externalRepository, never()).fetchDetail(eq(PURCHASE_RENTAL), any(), any());
    }

    @Test
    void 저장_중_중지는_수집_실패가_아니라_그대로_전파한다() {
        Row rental = row(801, RECENT);
        list(RENTAL_HOUSE, 1, 1, rental);
        detail(RENTAL_HOUSE, rental);
        doThrow(new DataPipelineStoppedException()).when(store).store(any());

        assertThatThrownBy(service::collect).isInstanceOf(DataPipelineStoppedException.class);
        verify(failures, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void 저장된_공고와_제목_게시일이_같고_열린_실패가_없으면_상세를_다시_받지_않는다() {
        Row unchanged = row(802, RECENT);
        Row pending = row(801, RECENT);
        list(RENTAL_HOUSE, 1, 2, unchanged, pending);
        list(PURCHASE_RENTAL, 1, 0);
        when(store.findUnchangedPbancNos(eq(RENTAL_HOUSE), any())).thenReturn(Set.of("802", "801"));
        when(failures.findPendingRequestDescriptions(eq(ExternalDataSource.GH_ANNOUNCEMENT), any()))
                .thenReturn(Set.of("pbancNo=801"));
        detail(RENTAL_HOUSE, pending);

        var report = service.collect();

        assertThat(report.skippedRequestCount()).isOne();
        assertThat(report.storedRowCount()).isOne();
        verify(externalRepository, never()).fetchDetail(RENTAL_HOUSE, "802", "01");
        verify(externalRepository).fetchDetail(RENTAL_HOUSE, "801", "01");
        verify(store).updateListValues(argThat(entries -> entries.size() == 1
                && entries.getFirst().pbancNo().equals("802")));
    }

    @Test
    void 정체성이_맞은_상세는_첨부를_못_읽어도_저장하고_실패를_열어_둔다() {
        Row rental = row(801, RECENT);
        list(RENTAL_HOUSE, 1, 1, rental);
        list(PURCHASE_RENTAL, 1, 0);
        when(externalRepository.fetchDetail(RENTAL_HOUSE, "801", "01")).thenReturn(GhAnnouncementFixtures
                .detail(RENTAL_HOUSE, rental).replace("pbancNo=801&atchFileSn", "pbancNo=900&atchFileSn"));

        var report = service.collect();

        assertThat(report.storedRowCount()).isOne();
        assertThat(report.failedRequestCount()).isOne();
        verify(store).store(argThat(snapshot -> snapshot.pbancNo().equals("801") && snapshot.attachments() == null
                && snapshot.districts().contains("다산 센트럴파크6단지")));
        verify(failures).record(eq(ExternalDataSource.GH_ANNOUNCEMENT), eq("pbancNo=801"), any(), any(), any());
        verify(failures, never()).resolve(ExternalDataSource.GH_ANNOUNCEMENT, "pbancNo=801");
    }

    @Test
    void 주택형마다_상세공급정보_팝업을_받아_면적과_세대수를_붙인다() {
        Row rental = row(801, RECENT);
        list(RENTAL_HOUSE, 1, 1, rental);
        list(PURCHASE_RENTAL, 1, 0);
        detail(RENTAL_HOUSE, rental);

        assertThat(service.collect().failedRequestCount()).isZero();

        verify(externalRepository).fetchHousingTypeDetail(eq(RENTAL_HOUSE), argThat(form ->
                form.get("houseTyCd").equals("36A") && form.get("splySlsrtCd").equals("30050")
                        && form.get("bizCd").equals("2011002") && form.get("rcritNmtm").equals("8")));
        verify(store).store(argThat(snapshot -> snapshot.districts().contains("\"supplyArea\":\"48.8048\"")
                && snapshot.districts().contains("\"householdCount\":\"522\"")));
    }

    @Test
    void 팝업을_못_받은_주택형은_상세_없이_저장하고_공고_실패를_열어_둔다() {
        Row rental = row(801, RECENT);
        list(RENTAL_HOUSE, 1, 1, rental);
        list(PURCHASE_RENTAL, 1, 0);
        detail(RENTAL_HOUSE, rental);
        when(externalRepository.fetchHousingTypeDetail(eq(RENTAL_HOUSE), argThat(form -> form != null
                && "33A".equals(form.get("houseTyCd"))))).thenReturn("<html>다른 주택형</html>");

        var report = service.collect();

        assertThat(report.storedRowCount()).isOne();
        assertThat(report.failedRequestCount()).isOne();
        verify(store).store(argThat(snapshot -> snapshot.districts().contains("\"supplyArea\"")));
        verify(failures).record(eq(ExternalDataSource.GH_ANNOUNCEMENT), eq("pbancNo=801"), argThat(error ->
                error.getMessage().contains("주택형 상세(다산 센트럴파크6단지 33A)")), any(), any());
    }

    @Test
    void 팝업이_호출_제한에_걸리면_남은_팝업과_다음_요청을_멈춘다() {
        Row rental = row(801, RECENT);
        list(RENTAL_HOUSE, 1, 1, rental);
        detail(RENTAL_HOUSE, rental);
        when(externalRepository.fetchHousingTypeDetail(eq(RENTAL_HOUSE), any()))
                .thenThrow(ExternalDataRequestException.rateLimited("429"));

        var report = service.collect();

        assertThat(report.rateLimitedRequestCount()).isOne();
        verify(externalRepository).fetchHousingTypeDetail(eq(RENTAL_HOUSE), any());
        verify(externalRepository, never()).fetchList(eq(PURCHASE_RENTAL), any(Integer.class));
    }

    private GhAnnouncementCollectionService service(int days) {
        return new GhAnnouncementCollectionService(externalRepository, new GhAnnouncementListParser(),
                new GhAnnouncementHtmlParser(), store, new GhHousingTypeDetails(), CollectionServiceTestFixture.retryWithoutDelay(), failures,
                Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC), days, Duration.ZERO);
    }

    private Row row(int pbancNo, String postedDate) {
        return new Row(Integer.toString(pbancNo), "국민임대", "공고 " + pbancNo, postedDate);
    }

    private void list(GhAnnouncementBoard board, int page, int total, Row... rows) {
        when(externalRepository.fetchList(board, page)).thenReturn(GhAnnouncementFixtures.list(board, page, total, rows));
    }

    private void detail(GhAnnouncementBoard board, Row row) {
        when(externalRepository.fetchDetail(board, row.pbancNo(), "01"))
                .thenReturn(GhAnnouncementFixtures.detail(board, row));
        lenient().when(externalRepository.fetchHousingTypeDetail(eq(board), any())).thenAnswer(invocation ->
                GhAnnouncementFixtures.housingTypeDetail(invocation.<Map<String, String>>getArgument(1).get("houseTyCd")));
    }
}
