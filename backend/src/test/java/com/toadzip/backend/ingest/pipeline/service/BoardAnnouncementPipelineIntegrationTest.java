package com.toadzip.backend.ingest.pipeline.service;

import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.RENTAL_HOUSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementExternalRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementSourceRepository;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementSourceRepository;
import org.aopalliance.intercept.MethodInterceptor;
import org.jsoup.Jsoup;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"ingest.sh.request-interval=0s", "ingest.sh.lookback-days=0",
        "ingest.gh.request-interval=0s", "ingest.gh.lookback-days=0"})
@ActiveProfiles("test")
class BoardAnnouncementPipelineIntegrationTest {

    private static final Row GH_ROW = new Row("801", "국민임대", "다산 국민임대 예비입주자 모집공고", "2026-07-10");
    @Autowired private DataPipelineExecutionService service;
    @Autowired private DataPipelineExecutionQueryService query;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ShAnnouncementExternalRepository shExternal;
    @MockitoBean private GhAnnouncementExternalRepository ghExternal;
    @MockitoBean(name = "dataPipelineExecutor") private Executor executor;
    private final AtomicReference<Runnable> task = new AtomicReference<>();

    @BeforeEach
    void prepare() {
        doAnswer(invocation -> { task.set(invocation.getArgument(0)); return null; }).when(executor).execute(any());
        var list = Jsoup.parse(ShAnnouncementFixtures.mainList());
        list.select("#listTb tbody > tr").get(1).remove();
        list.selectFirst("#mainform .mentcount strong").text("1");
        when(shExternal.fetchList(1)).thenReturn(list.html());
        when(shExternal.fetchDetail("100")).thenReturn(ShAnnouncementFixtures.detail());
        when(ghExternal.fetchList(RENTAL_HOUSE, 1))
                .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1, GH_ROW));
        when(ghExternal.fetchDetail(RENTAL_HOUSE, "801", "01"))
                .thenReturn(GhAnnouncementFixtures.detail(RENTAL_HOUSE, GH_ROW));
        when(ghExternal.fetchHousingTypeDetail(any(), any())).thenAnswer(invocation ->
                GhAnnouncementFixtures.housingTypeDetail(invocation.<Map<String, String>>getArgument(1).get("houseTyCd")));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM pending_collection_targets");
        for (String table : List.of("supply_rows", "announcement_complexes", "announcement_attachments")) {
            jdbc.update("DELETE FROM " + table + " WHERE announcement_id IN "
                    + "(SELECT id FROM announcements WHERE (source_announcement_identifier LIKE 'SH:%' OR source_announcement_identifier LIKE 'GH:%'))");
        }
        jdbc.update("DELETE FROM announcements WHERE (source_announcement_identifier LIKE 'SH:%' OR source_announcement_identifier LIKE 'GH:%')");
        jdbc.update("DELETE FROM gh_announcement_source");
        jdbc.update("DELETE FROM sh_announcement_source");
        jdbc.update("DELETE FROM external_data_collection_failures");
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {"SH_ANNOUNCEMENT_COLLECTION", "GH_ANNOUNCEMENT_COLLECTION"})
    void 기관별_실행은_수집_뒤_미확정_공고와_PDF를_만들고_정제_보고서를_보존한다(DataPipelineType type) {
        long notifications = count("notification_outbox_events");
        var accepted = service.start(type);
        task.get().run();
        var result = query.find(accepted.executionId());
        String agency = agency(type);

        assertThat(result.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(result.completedSteps()).containsExactly(agency + " 공고 수집·정제");
        assertThat(result.completedStepResults()).hasSize(1);
        assertThat(result.completedStepResults().getLast().report().toString()).contains("created=1", "failed=0");
        assertThat(jdbc.queryForObject("SELECT release_status FROM announcements WHERE provider = ?",
                String.class, agency)).isEqualTo("UNCONFIRMED");
        assertThat(count("announcement_attachments")).isOne();
        assertThat(count("notification_outbox_events")).isEqualTo(notifications);

        var again = service.start(type);
        task.get().run();
        assertThat(query.find(again.executionId()).status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(count("announcements")).isOne();
        assertThat(count("announcement_attachments")).isOne();
        assertThat(count("notification_outbox_events")).isEqualTo(notifications);
    }

    @ParameterizedTest
    @CsvSource({"SH_ANNOUNCEMENT_COLLECTION,false", "SH_ANNOUNCEMENT_COLLECTION,true",
            "GH_ANNOUNCEMENT_COLLECTION,false", "GH_ANNOUNCEMENT_COLLECTION,true"})
    void 상세_수집이_실패하거나_호출제한이면_그_글의_이전_원천은_정제하고_수집은_실패로_남긴다(DataPipelineType type, boolean limited) {
        // 이미 보관한 원천이 있는 글의 상세 재수집이 실패해도 같은 페이지의 정제는 보관한 원천으로 이어진다.
        var first = service.start(type);
        task.get().run();
        cleanAnnouncements();
        jdbc.update("UPDATE " + agency(type).toLowerCase() + "_announcement_source SET collected_at = '2020-01-01'");
        RuntimeException failure = limited ? ExternalDataRequestException.rateLimited("호출 제한")
                : new ExternalDataRequestException("상세 실패");
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            when(shExternal.fetchDetail("100")).thenThrow(failure);
        }
        if (type == DataPipelineType.GH_ANNOUNCEMENT_COLLECTION) {
            when(ghExternal.fetchDetail(RENTAL_HOUSE, "801", "01")).thenThrow(failure);
        }
        var accepted = service.start(type);
        task.get().run();
        var result = query.find(accepted.executionId());

        assertThat(result.status()).isEqualTo(limited ? DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS
                : DataPipelineExecutionStatus.FAILED);
        assertThat(count("announcements")).isOne();
        assertThat(count(agency(type).toLowerCase() + "_announcement_source")).isOne();
        assertThat(result.completedSteps()).doesNotContain(agency(type) + " 공고 수집·정제");
        if (limited) {
            assertThat(result.skippedSteps()).extracting(step -> step.stepName())
                    .containsExactly(agency(type) + " 공고 수집·정제");
        }
        assertThat(query.find(first.executionId()).completedStepResults()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {"SH_ANNOUNCEMENT_COLLECTION", "GH_ANNOUNCEMENT_COLLECTION"})
    void 두번째_페이지에서_호출제한이_걸려도_첫_페이지는_공고로_정제돼_있고_두번째_페이지는_대기로_남는다(DataPipelineType type) {
        boardList(type, 1, 11, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110);
        boardList(type, 2, 11, 111);
        for (int id = 101; id <= 111; id++) {
            boardDetail(type, id);
        }
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            doThrow(ExternalDataRequestException.rateLimited("호출 제한")).when(shExternal).fetchDetail("111");
        }
        else {
            doThrow(ExternalDataRequestException.rateLimited("호출 제한")).when(ghExternal)
                    .fetchDetail(RENTAL_HOUSE, "111", "01");
        }

        var accepted = service.start(type);
        task.get().run();
        var result = query.find(accepted.executionId());

        assertThat(result.status()).as(String.valueOf(result.failure())).isEqualTo(DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS);
        assertThat(result.skippedSteps()).extracting(step -> step.stepName())
                .containsExactly(agency(type) + " 공고 수집·정제");
        assertThat(count("announcements")).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcements WHERE source_announcement_identifier = ?",
                Long.class, agency(type) + ":111")).isZero();
        assertThat(count("pending_collection_targets")).isOne();
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {"SH_ANNOUNCEMENT_COLLECTION", "GH_ANNOUNCEMENT_COLLECTION"})
    void 한_원천_정제가_실패해도_다른_공고_커밋과_실패_보고서는_보존한다(DataPipelineType type) {
        String rejected = "GH:801";
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            rejected = "SH:100";
        }
        twoSources(type);
        jdbc.execute("ALTER TABLE announcements ADD CONSTRAINT board_pipeline_test_failure "
                + "CHECK (source_announcement_identifier <> '" + rejected + "') NOT VALID");
        try {
            var accepted = service.start(type);
            task.get().run();
            var result = query.find(accepted.executionId());

            assertThat(result.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
            assertThat(result.failure().stepName()).isEqualTo(agency(type) + " 공고 수집·정제");
            assertThat(result.failure().serverResponse().toString()).contains("failed=1", "created=1", rejected, "원천 정제를 완료하지 못했습니다")
                    .doesNotContain("board_pipeline_test_failure", "SQL");
            assertThat(count("announcements")).isOne();
            assertThat(jdbc.queryForObject("SELECT release_status FROM announcements", String.class))
                    .isEqualTo("UNCONFIRMED");
            assertThat(count("announcement_attachments")).isOne();
        }
        finally {
            jdbc.execute("ALTER TABLE announcements DROP CONSTRAINT board_pipeline_test_failure");
        }
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {"SH_ANNOUNCEMENT_COLLECTION", "GH_ANNOUNCEMENT_COLLECTION"})
    void 페이지의_정제_대상을_읽다_실패해도_수집_보고서와_저장한_원천은_보존하고_실패로_기록한다(DataPipelineType type) {
        twoSources(type);
        RefinementLookupFailure.enabled = true;
        try {
            var accepted = service.start(type);
            task.get().run();
            assertLookupFailureKeepsCollection(type, accepted.executionId());
        }
        finally {
            RefinementLookupFailure.enabled = false;
        }
    }

    private void assertLookupFailureKeepsCollection(DataPipelineType type, java.util.UUID executionId) {
        var result = query.find(executionId);

        assertThat(result.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(result.failure().stepName()).isEqualTo(agency(type) + " 공고 수집·정제");
        assertThat(result.failure().serverResponse()).as(String.valueOf(result.failure())).isNotNull();
        assertThat(result.failure().serverResponse().toString()).contains("failed=1", "collection");
        assertThat(count(agency(type).toLowerCase() + "_announcement_source")).isEqualTo(2);
        assertThat(count("announcements")).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {"SH_ANNOUNCEMENT_COLLECTION", "GH_ANNOUNCEMENT_COLLECTION"})
    void 정제_도중_실행_잠금을_잃으면_현재_원천만_롤백하고_앞선_커밋은_보존한다(DataPipelineType type) {
        twoSources(type);
        String interrupted = "GH:802";
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            // SH 원천은 등록일 순서이므로 200(10월 7일) 다음에 100(10월 8일)을 정제한다.
            interrupted = "SH:100";
        }
        jdbc.execute("""
                CREATE FUNCTION board_pipeline_lose_lease() RETURNS trigger LANGUAGE plpgsql AS $$
                DECLARE terminated boolean;
                BEGIN
                    IF NEW.source_announcement_identifier = '%s' THEN
                        SELECT pg_terminate_backend(pid, 5000) INTO terminated FROM pg_locks
                        WHERE locktype = 'advisory' AND granted
                          AND classid = (8432026090100001::bigint >> 32)::oid
                          AND objid = (8432026090100001::bigint & 4294967295)::oid
                          AND objsubid = 1
                          AND database = (SELECT oid FROM pg_database WHERE datname = current_database());
                        IF terminated IS DISTINCT FROM TRUE THEN
                            RAISE EXCEPTION '실행 잠금 세션을 종료하지 못했습니다';
                        END IF;
                    END IF;
                    RETURN NEW;
                END $$
                """.formatted(interrupted));
        try {
            jdbc.execute("CREATE TRIGGER board_pipeline_lose_lease BEFORE INSERT ON announcements "
                    + "FOR EACH ROW EXECUTE FUNCTION board_pipeline_lose_lease()");
            var accepted = service.start(type);
            task.get().run();

            assertThat(query.find(accepted.executionId()).status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
            assertThat(count("announcements")).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM announcements WHERE source_announcement_identifier = ?",
                    Long.class, interrupted)).isZero();
            assertThat(count("announcement_attachments")).isOne();
        }
        finally {
            jdbc.execute("DROP TRIGGER IF EXISTS board_pipeline_lose_lease ON announcements");
            jdbc.execute("DROP FUNCTION board_pipeline_lose_lease()");
        }
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {"SH_ANNOUNCEMENT_COLLECTION", "GH_ANNOUNCEMENT_COLLECTION"})
    void 실패와_미시도는_목록에서_사라져도_다음_신규_수집과_함께_처리한다(DataPipelineType type) {
        boardEntries(type, 100, 200, 300);
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            when(shExternal.fetchDetail("200")).thenThrow(ExternalDataRequestException.rateLimited("호출 제한"));
        }
        else {
            when(ghExternal.fetchDetail(RENTAL_HOUSE, "200", "01"))
                    .thenThrow(ExternalDataRequestException.rateLimited("호출 제한"));
        }
        var first = service.start(type);
        task.get().run();
        assertThat(count("pending_collection_targets")).isEqualTo(2);
        // 같은 페이지 안에서 호출 제한으로 멈춰도 그 앞에서 받은 100은 이미 공고로 정제돼 있다.
        assertThat(count("announcements")).isOne();
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            verify(shExternal, never()).fetchDetail("300");
        }
        else {
            verify(ghExternal, never()).fetchDetail(RENTAL_HOUSE, "300", "01");
        }
        // 이전 목록 없이 신규 공고만 보인다. 200 실패와 300 미시도 요청은 DB 입력으로 복구한다.
        boardEntries(type, 400);
        boardDetail(type, 200);
        var second = service.start(type);
        task.get().run();
        assertThat(query.find(second.executionId()).status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(count("pending_collection_targets")).isZero();
        assertThat(count("announcements")).isEqualTo(4);
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            verify(shExternal, times(1)).fetchDetail("100");
            verify(shExternal, times(2)).fetchDetail("200");
            verify(shExternal, times(1)).fetchDetail("300");
        }
        else {
            verify(ghExternal, times(1)).fetchDetail(RENTAL_HOUSE, "100", "01");
            verify(ghExternal, times(2)).fetchDetail(RENTAL_HOUSE, "200", "01");
            verify(ghExternal, times(1)).fetchDetail(RENTAL_HOUSE, "300", "01");
        }
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {"SH_ANNOUNCEMENT_COLLECTION", "GH_ANNOUNCEMENT_COLLECTION"})
    void 원천_저장에_실패하면_미완료_대상을_보존하고_복구_후에만_완료한다(DataPipelineType type) {
        boardEntries(type, 100, 200, 300);
        String table = agency(type).toLowerCase() + "_announcement_source";
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT pending_store_failure CHECK (source_key <> '"
                + agency(type) + ":200') NOT VALID");
        try {
            var first = service.start(type);
            task.get().run();
            assertThat(query.find(first.executionId()).status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
            assertThat(count(table)).isOne();
            assertThat(count("pending_collection_targets")).isEqualTo(2);
        }
        finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT pending_store_failure");
        }
        boardEntries(type, 400);
        var next = service.start(type);
        task.get().run();
        assertThat(query.find(next.executionId()).status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(count(table)).isEqualTo(4);
        assertThat(count("pending_collection_targets")).isZero();
    }

    @Test
    void 수집과_정제가_나뉘어_있던_지난_SH_실행_이력도_옛_단계_그대로_조회한다() {
        var accepted = service.start(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION);
        task.get().run();
        // 합치기 전 실행처럼 수집은 완료, 정제는 건너뜀으로 기록된 행으로 바꾼다.
        jdbc.update("UPDATE data_pipeline_execution_completed_steps SET completed_step = 'COLLECT_SH_ANNOUNCEMENTS' "
                + "WHERE data_pipeline_execution_id = (SELECT id FROM data_pipeline_executions WHERE execution_id = ?)",
                accepted.executionId());
        jdbc.update("INSERT INTO data_pipeline_execution_skipped_steps "
                + "(data_pipeline_execution_id, step_order, skipped_step, skip_reason) "
                + "SELECT id, 0, 'MAP_SH_ANNOUNCEMENTS', '수집이 호출 제한으로 완료되지 않았습니다.' "
                + "FROM data_pipeline_executions WHERE execution_id = ?", accepted.executionId());

        var result = query.find(accepted.executionId());

        assertThat(result.totalStepCount()).isEqualTo(2);
        assertThat(result.completedSteps()).containsExactly("SH 공고 원천 수집");
        assertThat(result.skippedSteps()).extracting(step -> step.stepName()).containsExactly("SH 공고 정제");
    }

    /**
     * 정제 서비스가 정제할 원천을 읽는 조회만 실패시킨다. 수집 저장도 같은 조회를 쓰므로 부른 쪽을 보고 가른다.
     */
    @TestConfiguration
    static class RefinementLookupFailure {

        static volatile boolean enabled;

        @Bean
        static BeanPostProcessor failRefinementLookup() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ShAnnouncementSourceRepository
                            || bean instanceof GhAnnouncementSourceRepository)) {
                        return bean;
                    }
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        boolean fromRefinement = Arrays.stream(Thread.currentThread().getStackTrace())
                                .anyMatch(frame -> frame.getClassName().endsWith("RefinementService$Run"));
                        if (enabled && fromRefinement) {
                            throw new IllegalStateException("조회 실패");
                        }
                        return invocation.proceed();
                    });
                    return factory.getProxy();
                }
            };
        }
    }

    private void boardEntries(DataPipelineType type, int... ids) {
        boardList(type, 1, ids.length, ids);
        for (int id : ids) {
            boardDetail(type, id);
        }
    }

    /** 전체 total건 중 page번째 목록 페이지에 ids의 글이 있다. */
    private void boardList(DataPipelineType type, int page, int total, int... ids) {
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            var list = Jsoup.parse(ShAnnouncementFixtures.mainList());
            var template = list.selectFirst("#listTb tbody > tr").clone();
            list.selectFirst("#listTb tbody").empty();
            list.selectFirst("#mainform .mentcount").html("총 <strong>" + total + "</strong> 건 [" + page + "/"
                    + (total + 9) / 10 + "페이지]");
            for (int id : ids) {
                var row = template.clone();
                row.selectFirst("a").attr("onclick", "getDetailView('" + id + "')");
                list.selectFirst("#listTb tbody").appendChild(row);
            }
            when(shExternal.fetchList(page)).thenReturn(list.html());
        }
        else {
            var rows = Arrays.stream(ids).mapToObj(id -> new Row(Integer.toString(id), GH_ROW.supplyType(),
                    GH_ROW.title(), GH_ROW.postedDate())).toArray(Row[]::new);
            when(ghExternal.fetchList(RENTAL_HOUSE, page))
                    .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, page, total, rows));
        }
    }

    private void boardDetail(DataPipelineType type, int id) {
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            doReturn(ShAnnouncementFixtures.detail().replace("\"100\"", "\"" + id + "\""))
                    .when(shExternal).fetchDetail(Integer.toString(id));
        }
        else {
            var row = new Row(Integer.toString(id), GH_ROW.supplyType(), GH_ROW.title(), GH_ROW.postedDate());
            doReturn(GhAnnouncementFixtures.detail(RENTAL_HOUSE, row))
                    .when(ghExternal).fetchDetail(RENTAL_HOUSE, Integer.toString(id), "01");
        }
    }

    private void twoSources(DataPipelineType type) {
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            when(shExternal.fetchList(1)).thenReturn(ShAnnouncementFixtures.mainList());
            when(shExternal.fetchDetail("200")).thenReturn(ShAnnouncementFixtures.detail()
                    .replace("주택 입주자 모집", "다른 입주자 모집").replace("2026-10-08", "2026-10-07")
                    .replace("\"100\"", "\"200\""));
        }
        if (type == DataPipelineType.GH_ANNOUNCEMENT_COLLECTION) {
            var other = new Row("802", GH_ROW.supplyType(), "다른 국민임대 모집공고", GH_ROW.postedDate());
            when(ghExternal.fetchList(RENTAL_HOUSE, 1))
                    .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 2, GH_ROW, other));
            when(ghExternal.fetchDetail(RENTAL_HOUSE, "802", "01"))
                    .thenReturn(GhAnnouncementFixtures.detail(RENTAL_HOUSE, other));
        }
    }

    private void cleanAnnouncements() {
        for (String table : List.of("supply_rows", "announcement_complexes", "announcement_attachments")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM announcements");
    }

    private String agency(DataPipelineType type) {
        if (type == DataPipelineType.SH_ANNOUNCEMENT_COLLECTION) {
            return "SH";
        }
        return "GH";
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
