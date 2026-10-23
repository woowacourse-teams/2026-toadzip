package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.ShAnnouncementSnapshot;
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
class ShAnnouncementStoreTest {

    private static final Instant FIRST = Instant.parse("2026-10-05T00:00:00Z");
    @Autowired
    private ShAnnouncementSourceRepository repository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void 재수집은_같은_게시글에_정제_내용과_수집_시각을_갱신한다() {
        store(FIRST).store(snapshot("100", "제목", "첫 원문"));
        store(FIRST.plusSeconds(60)).store(snapshot("100", "정정 제목", "정정 원문"));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findAll()).singleElement().satisfies(source -> {
            assertThat(source.getSourceKey()).isEqualTo("SH:100");
            assertThat(source.getTitle()).isEqualTo("정정 제목");
            assertThat(source.getCollectedAt()).isEqualTo(FIRST.plusSeconds(60));
            assertThat(source.getBodyText()).isEqualTo("정정 원문");
            assertThat(source.getAttachments()).isEqualTo("[]");
        });
    }

    @Test
    void 수집_범위에_없는_기존_원천도_보존한다() {
        store(FIRST).store(snapshot("100", "제목", "원문"));
        store(FIRST).store(snapshot("200", "다른 제목", "다른 원문"));
        store(FIRST.plusSeconds(60)).store(snapshot("100", "제목", "원문"));

        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findBySourceKey("SH:200").orElseThrow().getCollectedAt()).isEqualTo(FIRST);
    }

    private ShAnnouncementStore store(Instant now) {
        return new ShAnnouncementStore(repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    private ShAnnouncementSnapshot snapshot(String seq, String title, String raw) {
        return new ShAnnouncementSnapshot(seq, title, "공급부", LocalDate.parse("2026-10-02"),
                ShAnnouncementExternalRepository.detailUrl(seq), ShAnnouncementExternalRepository.LIST_URL,
                raw, "[]");
    }
}
