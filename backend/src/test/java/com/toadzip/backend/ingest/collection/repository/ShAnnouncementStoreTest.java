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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ShAnnouncementStoreTest {

    private static final Instant FIRST = Instant.parse("2026-10-05T00:00:00Z");
    @Autowired
    private ShAnnouncementSourceRepository repository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void repeatedCollectionUsesSameKeyAndOnlyRefreshesObservedTimeForHtmlNoise() {
        store(FIRST).store(snapshot("100", "제목", "<p>본문</p>", "[]", "조회수 100"));
        assertThat(store(FIRST.plusSeconds(60)).store(snapshot("100", "제목", "<p>본문</p>", "[]", "조회수 200")))
                .isEqualTo("unchanged");
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findAll()).singleElement().satisfies(source -> {
            assertThat(source.getSourceKey()).isEqualTo("SH:m_247:100");
            assertThat(source.getChangedAt()).isEqualTo(FIRST);
            assertThat(source.getCollectedAt()).isEqualTo(FIRST.plusSeconds(60));
            assertThat(source.getRawDetailHtml()).isEqualTo("조회수 200");
        });
    }

    @Test
    void titleBodyAndAttachmentChangesUpdateChangedTimeWithoutDuplicatingPost() {
        store(FIRST).store(snapshot("100", "제목", "<p>본문</p>", "[]", "원문"));
        assertThat(store(FIRST.plusSeconds(1)).store(snapshot("100", "정정 제목", "<p>본문</p>", "[]", "원문")))
                .isEqualTo("changed");
        assertThat(store(FIRST.plusSeconds(2)).store(snapshot("100", "정정 제목", "<p>정정 본문</p>", "[]", "원문")))
                .isEqualTo("changed");
        assertThat(store(FIRST.plusSeconds(3)).store(snapshot("100", "정정 제목", "<p>정정 본문</p>",
                "[{\"fileSeq\":\"7\"}]", "원문"))).isEqualTo("changed");

        assertThat(repository.findAll()).singleElement().satisfies(source ->
                assertThat(source.getChangedAt()).isEqualTo(FIRST.plusSeconds(3)));
    }

    @Test
    void collectingSubsetLeavesOtherStoredSourcesIntact() {
        store(FIRST).store(snapshot("100", "제목", "<p>본문</p>", "[]", "원문"));
        store(FIRST).store(snapshot("200", "다른 제목", "<p>본문</p>", "[]", "다른 원문"));
        store(FIRST.plusSeconds(60)).store(snapshot("100", "제목", "<p>본문</p>", "[]", "원문"));

        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findBySourceKey("SH:m_247:200").orElseThrow().getCollectedAt()).isEqualTo(FIRST);
    }

    private ShAnnouncementStore store(Instant now) {
        return new ShAnnouncementStore(repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    private ShAnnouncementSnapshot snapshot(String seq, String title, String body, String files, String raw) {
        return new ShAnnouncementSnapshot(seq, title, "공급부", LocalDate.parse("2026-10-02"), body, "본문", files,
                ShAnnouncementExternalRepository.detailUrl(seq), ShAnnouncementExternalRepository.LIST_URL, raw, raw);
    }
}
