package com.toadzip.backend.ingest.pipeline.service;

import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.RENTAL_HOUSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementExternalRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
        for (String table : List.of("supply_rows", "announcement_complexes", "announcement_attachments")) {
            jdbc.update("DELETE FROM " + table + " WHERE announcement_id IN "
                    + "(SELECT id FROM announcements WHERE source_announcement_identifier IN ('GH:801', 'GH:802', 'SH:100', 'SH:200'))");
        }
        jdbc.update("DELETE FROM announcements WHERE source_announcement_identifier IN ('GH:801', 'GH:802', 'SH:100', 'SH:200')");
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
        assertThat(result.completedSteps()).containsExactly(agency + " 공고 원천 수집", agency + " 공고 정제");
        assertThat(result.completedStepResults()).hasSize(2);
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
    void 수집_실패나_호출제한이면_저장된_원천도_자동_정제하지_않는다(DataPipelineType type, boolean limited) {
        // 이미 보관한 성공 원천이 있어도 이번 수집이 실패하면 제품 공고를 만들지 않는다.
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
        assertThat(count("announcements")).isZero();
        assertThat(count(agency(type).toLowerCase() + "_announcement_source")).isOne();
        assertThat(result.completedSteps()).doesNotContain(agency(type) + " 공고 정제");
        if (limited) {
            assertThat(result.skippedSteps()).extracting(step -> step.stepName())
                    .contains(agency(type) + " 공고 정제");
        }
        assertThat(query.find(first.executionId()).completedStepResults()).hasSize(2);
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
            assertThat(result.failure().stepName()).isEqualTo(agency(type) + " 공고 정제");
            assertThat(result.failure().serverResponse().toString()).contains("failed=1", "created=1");
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
