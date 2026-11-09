package com.toadzip.backend.ingest.pipeline.service;

import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.RENTAL_HOUSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementExternalRepository;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 게시판 정정공고를 원공고에 잇는 규칙을 GH 실행 전체로 확인한다. SH는 같은 연결기를 다른 이름 규칙으로 쓴다. */
@SpringBootTest(properties = {"ingest.gh.request-interval=0s", "ingest.gh.lookback-days=0",
        "ingest.sh.request-interval=0s", "ingest.sh.lookback-days=0"})
@ActiveProfiles("test")
class BoardRevisionLinkingIntegrationTest {

    private static final String TITLE = "다산 국민임대 예비입주자 모집공고";
    @Autowired private DataPipelineExecutionService service;
    @Autowired private DataPipelineExecutionQueryService query;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GhAnnouncementExternalRepository external;
    @MockitoBean private ShAnnouncementExternalRepository shExternal;
    @MockitoBean(name = "dataPipelineExecutor") private Executor executor;
    private final AtomicReference<Runnable> task = new AtomicReference<>();

    @BeforeEach
    void prepare() {
        doAnswer(invocation -> { task.set(invocation.getArgument(0)); return null; }).when(executor).execute(any());
        when(external.fetchHousingTypeDetail(any(), any())).thenAnswer(invocation ->
                GhAnnouncementFixtures.housingTypeDetail(invocation.<Map<String, String>>getArgument(1).get("houseTyCd")));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM pending_collection_targets");
        for (String table : List.of("supply_rows", "announcement_complexes", "announcement_attachments")) {
            jdbc.update("DELETE FROM " + table + " WHERE announcement_id IN "
                    + "(SELECT id FROM announcements WHERE source_announcement_identifier LIKE 'GH:%' OR source_announcement_identifier LIKE 'SH:%')");
        }
        jdbc.update("UPDATE announcements SET previous_announcement_id = NULL WHERE source_announcement_identifier ~ '^(GH|SH):'");
        jdbc.update("DELETE FROM announcements WHERE source_announcement_identifier ~ '^(GH|SH):'");
        jdbc.update("DELETE FROM gh_announcement_source");
        jdbc.update("DELETE FROM sh_announcement_source");
        jdbc.update("DELETE FROM external_data_collection_failures");
    }

    @Test
    void 원공고가_뒤_페이지에서_나중에_정제돼도_실행_끝에서_정정공고를_잇는다() {
        // 첫 페이지의 정정공고를 정제할 때는 원공고가 아직 없고, 둘째 페이지의 원공고가 정제된 뒤 마무리에서 잇는다.
        List<Row> first = new java.util.ArrayList<>();
        first.add(row("802", "[정정공고] " + TITLE, "2026-07-12"));
        for (int index = 0; index < 9; index++) {
            first.add(row("81" + index, "다른 공고 " + index, "2026-07-11"));
        }
        var result = runPages(first, List.of(row("801", TITLE, "2026-07-10")));

        assertThat(result.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(previousOf("GH:802")).isEqualTo("GH:801");
        assertThat(jdbc.queryForObject("SELECT status FROM announcements WHERE source_announcement_identifier = 'GH:802'",
                String.class)).isEqualTo("CORRECTION");
        assertThat(result.completedStepResults().getLast().report().toString())
                .contains("revisionsLinked=1", "revisionsUnlinked=0");
    }

    @Test
    void 원공고가_먼저_정제됐으면_정제할_때_바로_잇는다() {
        var result = run(row("801", TITLE, "2026-07-10"), row("802", "(수정) " + TITLE, "2026-07-12"));

        assertThat(previousOf("GH:802")).isEqualTo("GH:801");
        assertThat(result.completedStepResults().getLast().report().toString()).contains("revisionsLinked=1");
    }

    @Test
    void 원공고가_둘이면_잇지_않고_연결_못한_정정공고로_보고한다() {
        var result = run(row("803", "[정정공고] " + TITLE, "2026-07-12"), row("801", TITLE, "2026-07-10"),
                row("802", TITLE, "2026-07-10"));

        assertThat(previousOf("GH:803")).isNull();
        assertThat(result.completedStepResults().getLast().report().toString())
                .contains("revisionsLinked=0", "revisionsUnlinked=1");
    }

    @Test
    void 원공고_공고일이_90일보다_이르면_잇지_않는다() {
        run(row("802", "[정정공고] " + TITLE, "2026-07-12"), row("801", TITLE, "2026-04-01"));

        assertThat(previousOf("GH:802")).isNull();
    }

    @Test
    void 원공고_공고일이_정정공고보다_늦으면_잇지_않는다() {
        run(row("802", "[정정공고] " + TITLE, "2026-07-10"), row("801", TITLE, "2026-07-12"));

        assertThat(previousOf("GH:802")).isNull();
    }

    @Test
    void 이미_다른_정정공고가_이은_원공고는_고르지_않는다() {
        run(row("803", "[정정공고] " + TITLE, "2026-07-14"), row("802", "(수정) " + TITLE, "2026-07-12"),
                row("801", TITLE, "2026-07-10"));

        // 802는 원공고 801을 잇고, 803은 가장 최근 후보인 802를 잇는다(정정의 정정).
        assertThat(previousOf("GH:802")).isEqualTo("GH:801");
        assertThat(previousOf("GH:803")).isEqualTo("GH:802");
    }

    @Test
    void 같은_글의_제목이_정정_표시로_바뀌어도_자기_자신에는_잇지_않는다() {
        run(row("801", TITLE, "2026-07-10"));
        run(row("801", "[정정공고] " + TITLE, "2026-07-10"));

        assertThat(previousOf("GH:801")).isNull();
    }

    @Test
    void 원공고_없이_공고일이_같은_정정공고_둘은_서로를_원공고로_잇지_않는다() {
        var result = run(row("802", "[정정공고] " + TITLE, "2026-07-12"), row("803", "(수정) " + TITLE, "2026-07-12"));

        // 먼저 정제한 802는 후보가 없어 마무리까지 못 잇고, 803은 802에 잇는다. 마무리에서 802가 803에 이어져 순환이 되면 안 된다.
        assertThat(previousOf("GH:802")).isNull();
        assertThat(previousOf("GH:803")).isEqualTo("GH:802");
        assertThat(result.completedStepResults().getLast().report().toString()).contains("revisionsUnlinked=1");
    }

    @Test
    void 휴지통_원공고는_잇지_않는다() {
        run(row("801", TITLE, "2026-07-10"));
        jdbc.update("UPDATE announcements SET admin_deleted = true WHERE source_announcement_identifier = 'GH:801'");

        run(row("801", TITLE, "2026-07-10"), row("802", "[정정공고] " + TITLE, "2026-07-12"));

        assertThat(previousOf("GH:802")).isNull();
    }

    @Test
    void 같은_실행을_다시_해도_연결은_그대로이고_바뀐_공고로_세지_않는다() {
        run(row("801", TITLE, "2026-07-10"), row("802", "[정정공고] " + TITLE, "2026-07-12"));

        var again = run(row("801", TITLE, "2026-07-10"), row("802", "[정정공고] " + TITLE, "2026-07-12"));

        assertThat(previousOf("GH:802")).isEqualTo("GH:801");
        assertThat(again.completedStepResults().getLast().report().toString())
                .contains("revisionsLinked=0", "revisionsUnlinked=0", "updated=0");
    }

    @Test
    void SH는_뒤_정정_표시로_정정공고를_알아보고_원공고에_잇는다() {
        var list = org.jsoup.Jsoup.parse(ShAnnouncementFixtures.mainList());
        var template = list.selectFirst("#listTb tbody > tr").clone();
        list.selectFirst("#listTb tbody").empty();
        for (String[] post : new String[][] {{"100", "주택 입주자 모집", "2026-10-08"},
                {"200", "주택 입주자 모집(정정)", "2026-10-09"}}) {
            var row = template.clone();
            row.selectFirst("a").attr("onclick", "getDetailView('" + post[0] + "')").text(post[1]);
            row.select("td").get(3).text(post[2]);
            list.selectFirst("#listTb tbody").appendChild(row);
            when(shExternal.fetchDetail(post[0])).thenReturn(ShAnnouncementFixtures.detail()
                    .replace("주택 입주자 모집", post[1]).replace("2026-10-08", post[2]).replace("\"100\"", "\"" + post[0] + "\""));
        }
        when(shExternal.fetchList(1)).thenReturn(list.html());
        var accepted = service.start(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION);
        task.get().run();

        assertThat(query.find(accepted.executionId()).status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(previousOf("SH:200")).isEqualTo("SH:100");
    }

    @Test
    void 호출_제한으로_수집이_멈춘_뒤에도_이미_정제한_정정공고를_마무리에서_잇는다() {
        List<Row> first = new java.util.ArrayList<>();
        first.add(row("802", "[정정공고] " + TITLE, "2026-07-12"));
        for (int index = 0; index < 9; index++) {
            first.add(row("81" + index, "다른 공고 " + index, "2026-07-11"));
        }
        // 둘째 페이지 목록은 받았지만 원공고 상세에서 429가 나므로 원공고는 정제되지 않는다. 그래도 마무리는 예외 없이 돈다.
        when(external.fetchDetail(RENTAL_HOUSE, "801", "01"))
                .thenThrow(com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException.rateLimited("호출 제한"));
        var accepted = runPagesWithoutDetails(first, List.of(row("801", TITLE, "2026-07-10")));

        assertThat(accepted.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS);
        assertThat(previousOf("GH:802")).isNull();
        assertThat(accepted.skippedSteps()).extracting(step -> step.stepName()).containsExactly("GH 공고 수집·정제");
        assertThat(accepted.skippedSteps().getFirst().serverResponse().toString()).contains("revisionsUnlinked=1");
    }

    @Test
    void 정정공고를_이은_뒤_정제가_실패해_되돌아가면_연결_수에_세지_않는다() {
        jdbc.execute("""
                CREATE FUNCTION board_revision_fail_after_link() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM announcements a WHERE a.id = NEW.announcement_id
                               AND a.source_announcement_identifier = 'GH:802') THEN
                        RAISE EXCEPTION '연결 뒤 주입한 실패';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        try {
            jdbc.execute("CREATE TRIGGER board_revision_fail_after_link BEFORE INSERT ON announcement_complexes "
                    + "FOR EACH ROW EXECUTE FUNCTION board_revision_fail_after_link()");

            var result = run(row("801", TITLE, "2026-07-10"), row("802", "[정정공고] " + TITLE, "2026-07-12"));

            assertThat(result.failure().serverResponse()).as(String.valueOf(result.failure())).isNotNull();
            assertThat(result.failure().serverResponse().toString()).contains("failed=1", "revisionsLinked=0");
            assertThat(previousOf("GH:802")).isNull();
        }
        finally {
            jdbc.execute("DROP TRIGGER IF EXISTS board_revision_fail_after_link ON announcement_complexes");
            jdbc.execute("DROP FUNCTION IF EXISTS board_revision_fail_after_link()");
        }
    }

    @Test
    void 후속_안내_공고는_정정공고로_보지_않는다() {
        run(row("802", TITLE + " 서류심사대상자 발표", "2026-07-12"), row("801", TITLE, "2026-07-10"));

        assertThat(previousOf("GH:802")).isNull();
    }

    private com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse run(Row... rows) {
        return runPages(List.of(rows), List.of());
    }

    /** 목록이 한 페이지(10건)보다 길면 첫 페이지 뒤 나머지는 둘째 페이지에 있다. */
    private com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse runPages(List<Row> first, List<Row> second) {
        int total = first.size() + second.size();
        when(external.fetchList(RENTAL_HOUSE, 1))
                .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, total, first.toArray(Row[]::new)));
        if (!second.isEmpty()) {
            when(external.fetchList(RENTAL_HOUSE, 2))
                    .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 2, total, second.toArray(Row[]::new)));
        }
        for (Row row : java.util.stream.Stream.concat(first.stream(), second.stream()).toList()) {
            when(external.fetchDetail(RENTAL_HOUSE, row.pbancNo(), "01"))
                    .thenReturn(GhAnnouncementFixtures.detail(RENTAL_HOUSE, row));
        }
        var accepted = service.start(DataPipelineType.GH_ANNOUNCEMENT_COLLECTION);
        task.get().run();
        return query.find(accepted.executionId());
    }

    private com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse runPagesWithoutDetails(
            List<Row> first, List<Row> second) {
        int total = first.size() + second.size();
        when(external.fetchList(RENTAL_HOUSE, 1))
                .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, total, first.toArray(Row[]::new)));
        when(external.fetchList(RENTAL_HOUSE, 2))
                .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 2, total, second.toArray(Row[]::new)));
        for (Row row : first) {
            when(external.fetchDetail(RENTAL_HOUSE, row.pbancNo(), "01"))
                    .thenReturn(GhAnnouncementFixtures.detail(RENTAL_HOUSE, row));
        }
        var accepted = service.start(DataPipelineType.GH_ANNOUNCEMENT_COLLECTION);
        task.get().run();
        return query.find(accepted.executionId());
    }

    private Row row(String pbancNo, String title, String postedDate) {
        return new Row(pbancNo, "국민임대", title, postedDate);
    }

    private String previousOf(String sourceKey) {
        return jdbc.query("""
                SELECT p.source_announcement_identifier FROM announcements a
                LEFT JOIN announcements p ON p.id = a.previous_announcement_id
                WHERE a.source_announcement_identifier = ?
                """, rs -> rs.next() ? rs.getString(1) : null, sourceKey);
    }
}
