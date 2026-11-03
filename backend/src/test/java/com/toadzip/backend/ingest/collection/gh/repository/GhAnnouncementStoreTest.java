package com.toadzip.backend.ingest.collection.gh.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementSnapshot;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
            assertThat(source.getBodyText()).isEqualTo("정정 원문");
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

    private GhAnnouncementStore store(Instant now) {
        return new GhAnnouncementStore(repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    private GhAnnouncementSnapshot snapshot(String pbancNo, String title, String status, String body) {
        var board = GhAnnouncementBoard.RENTAL_HOUSE;
        return new GhAnnouncementSnapshot(board, pbancNo, "01", title, "국민임대", "남양주시",
                LocalDate.parse("2026-10-02"), null, status, board.detailUrl(pbancNo, "01"), board.listUrl(1),
                body, "[]");
    }
}
