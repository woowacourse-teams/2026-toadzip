package com.toadzip.backend.ingest.collection.gh.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementSnapshot;
import com.toadzip.backend.ingest.collection.gh.dto.GhAnnouncementPage.Entry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class GhAnnouncementStoreTest {

    private static final Instant FIRST = Instant.parse("2026-10-05T00:00:00Z");
    @Autowired
    private GhAnnouncementSourceRepository repository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void 재수집은_같은_공고에_정제_내용과_수집_시각을_갱신한다() {
        store(FIRST).store(snapshot("801", "제목", "접수중", "첫 원문"));
        store(FIRST.plusSeconds(60)).store(snapshot("801", "정정 제목", "접수마감", "정정 원문"));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findAll()).singleElement().satisfies(source -> {
            assertThat(source.getSourceKey()).isEqualTo("GH:801");
            assertThat(source.getBoard()).isEqualTo(GhAnnouncementBoard.RENTAL_HOUSE);
            assertThat(source.getTitle()).isEqualTo("정정 제목");
            assertThat(source.getStatus()).isEqualTo("접수마감");
            assertThat(source.getNotice()).isEqualTo("정정 원문");
            assertThat(source.getAttachments()).isEqualTo("[]");
            assertThat(source.getCollectedAt()).isEqualTo(FIRST.plusSeconds(60));
        });
    }

    @Test
    void 수집_범위에_없는_기존_원천도_보존한다() {
        store(FIRST).store(snapshot("801", "제목", "접수중", "원문"));
        store(FIRST).store(snapshot("802", "다른 제목", "접수중", "다른 원문"));
        store(FIRST.plusSeconds(60)).store(snapshot("801", "제목", "접수중", "원문"));

        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findBySourceKey("GH:802").orElseThrow().getCollectedAt()).isEqualTo(FIRST);
    }

    @Test
    void 읽지_못한_구획은_저장값을_덮지_않는다() {
        store(FIRST).store(snapshot("801", "제목", "접수중", "원문"));
        var board = GhAnnouncementBoard.RENTAL_HOUSE;
        store(FIRST.plusSeconds(60)).store(new GhAnnouncementSnapshot(board, "801", "01", "제목", "국민임대", "남양주시",
                LocalDate.parse("2026-10-02"), null, "접수마감", board.detailUrl("801", "01"), board.listUrl(1),
                null, null, null, null, null));
        entityManager.flush();
        entityManager.clear();

        var source = repository.findBySourceKey("GH:801").orElseThrow();
        assertThat(source.getNotice()).isEqualTo("원문");
        assertThat(source.getReception()).isEqualTo("{}");
        assertThat(source.getOtherNotes()).isEmpty();
        assertThat(source.getDistricts()).isEqualTo("[]");
        assertThat(source.getAttachments()).isEqualTo("[]");
        assertThat(source.getStatus()).isEqualTo("접수마감");
    }

    @Test
    void 같은_게시판에서_제목과_게시일이_같은_공고만_바뀌지_않은_공고로_본다() {
        store(FIRST).store(snapshot("801", "제목", "접수중", "원문"));
        store(FIRST).store(snapshot("802", "제목", "접수중", "원문"));
        store(FIRST).store(snapshot("803", "제목", "접수중", "원문"));
        var entries = List.of(entry("801", "제목", "2026-10-02"), entry("802", "정정 제목", "2026-10-02"),
                entry("803", "제목", "2026-10-03"), entry("804", "제목", "2026-10-02"));

        assertThat(store(FIRST).findUnchangedPbancNos(GhAnnouncementBoard.RENTAL_HOUSE, entries))
                .containsExactly("801");
        assertThat(store(FIRST).findUnchangedPbancNos(GhAnnouncementBoard.PURCHASE_RENTAL, entries)).isEmpty();
    }

    @Test
    void 일부_구획을_읽지_못한_채_저장된_공고는_바뀌지_않은_공고로_보지_않는다() {
        var board = GhAnnouncementBoard.RENTAL_HOUSE;
        store(FIRST).store(new GhAnnouncementSnapshot(board, "801", "01", "제목", "국민임대", "남양주시",
                LocalDate.parse("2026-10-02"), null, "접수중", board.detailUrl("801", "01"), board.listUrl(1),
                "[]", "{}", "원문", "", null));
        store(FIRST).store(new GhAnnouncementSnapshot(board, "802", "01", "제목", "국민임대", "남양주시",
                LocalDate.parse("2026-10-02"), null, "접수중", board.detailUrl("802", "01"), board.listUrl(1),
                null, "{}", "원문", "", "[]"));

        assertThat(store(FIRST).findUnchangedPbancNos(board,
                List.of(entry("801", "제목", "2026-10-02"), entry("802", "제목", "2026-10-02")))).isEmpty();
    }

    @Test
    void 제목이_바뀐_새_판에서_못_읽은_구획은_옛_판_값을_남기지_않는다() {
        store(FIRST).store(snapshot("801", "제목", "접수중", "원문"));
        var board = GhAnnouncementBoard.RENTAL_HOUSE;
        store(FIRST.plusSeconds(60)).store(new GhAnnouncementSnapshot(board, "801", "01", "[정정] 제목", "국민임대",
                "남양주시", LocalDate.parse("2026-10-02"), null, "접수중", board.detailUrl("801", "01"),
                board.listUrl(1), null, "{}", "정정 원문", "", "[]"));
        entityManager.flush();
        entityManager.clear();

        var source = repository.findBySourceKey("GH:801").orElseThrow();
        assertThat(source.getDistricts()).isNull();
        assertThat(source.getNotice()).isEqualTo("정정 원문");
        assertThat(store(FIRST).findUnchangedPbancNos(board, List.of(entry("801", "[정정] 제목", "2026-10-02")))).isEmpty();
    }

    @Test
    void 같은_판에서_팝업을_못_받은_주택형은_저장돼_있던_상세를_유지한다() {
        var board = GhAnnouncementBoard.RENTAL_HOUSE;
        String request = "\"detailRequest\":{\"houseTyCd\":\"33A\"}";
        String withDetail = "[{\"name\":\"다산\",\"housingTypes\":[{" + request + ",\"detail\":{\"supplyArea\":\"48.8\"}}]}]";
        String withoutDetail = "[{\"name\":\"다산\",\"housingTypes\":[{" + request + "}]}]";
        store(FIRST).store(districts(board, "제목", withDetail));
        store(FIRST.plusSeconds(60)).store(districts(board, "제목", withoutDetail));
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.findBySourceKey("GH:801").orElseThrow().getDistricts()).contains("\"supplyArea\":\"48.8\"");

        store(FIRST.plusSeconds(120)).store(districts(board, "[정정] 제목", withoutDetail));
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.findBySourceKey("GH:801").orElseThrow().getDistricts()).doesNotContain("supplyArea");
    }

    private GhAnnouncementSnapshot districts(GhAnnouncementBoard board, String title, String districts) {
        return new GhAnnouncementSnapshot(board, "801", "01", title, "국민임대", "남양주시", LocalDate.parse("2026-10-02"),
                null, "접수중", board.detailUrl("801", "01"), board.listUrl(1), districts, "{}", "", "", "[]");
    }

    private Entry entry(String pbancNo, String title, String postedDate) {
        return new Entry(pbancNo, "01", "국민임대", title, "남양주시", LocalDate.parse(postedDate), null, null);
    }

    private GhAnnouncementStore store(Instant now) {
        return new GhAnnouncementStore(repository, Clock.fixed(now, ZoneOffset.UTC), new GhHousingTypeDetails());
    }

    private GhAnnouncementSnapshot snapshot(String pbancNo, String title, String status, String notice) {
        var board = GhAnnouncementBoard.RENTAL_HOUSE;
        return new GhAnnouncementSnapshot(board, pbancNo, "01", title, "국민임대", "남양주시",
                LocalDate.parse("2026-10-02"), null, status, board.detailUrl(pbancNo, "01"), board.listUrl(1),
                "[]", "{}", notice, "", "[]");
    }
}
