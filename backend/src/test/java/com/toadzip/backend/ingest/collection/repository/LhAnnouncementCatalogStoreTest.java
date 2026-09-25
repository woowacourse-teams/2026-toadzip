package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementCatalogPage.Entry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LhAnnouncementCatalogStoreTest {

    private static final Instant FIRST = Instant.parse("2026-09-25T00:00:00Z");

    @Autowired
    private LhAnnouncementCatalogSourceRepository repository;

    @Test
    void 같은_목록을_다시_관찰해도_변경시각을_갱신하지_않는다() {
        store(FIRST).store(List.of(entry("100", "공고중", "original")));
        store(FIRST.plusSeconds(60)).store(List.of(entry("100", "공고중", "different RNUM and ALL_CNT")));

        assertThat(repository.findAll()).singleElement().satisfies(source -> {
            assertThat(source.getChangedAt()).isEqualTo(FIRST);
            assertThat(source.getCollectedAt()).isEqualTo(FIRST.plusSeconds(60));
        });
    }

    @Test
    void 공고상태가_바뀌면_변경시각을_갱신한다() {
        store(FIRST).store(List.of(entry("100", "공고중", "{}")));
        store(FIRST.plusSeconds(60)).store(List.of(entry("100", "정정공고중", "{}")));

        assertThat(repository.findAll()).singleElement().satisfies(source ->
                assertThat(source.getChangedAt()).isEqualTo(FIRST.plusSeconds(60))
        );
    }

    @Test
    void 현재_검색범위에_없는_공고_원천을_삭제하지_않는다() {
        store(FIRST).store(List.of(entry("100", "공고중", "{}"), entry("200", "공고중", "{}")));
        store(FIRST.plusSeconds(60)).store(List.of(entry("200", "공고중", "{}")));

        assertThat(repository.findAllByPanIdIn(List.of("100"))).singleElement().satisfies(source ->
                assertThat(source.getCollectedAt()).isEqualTo(FIRST)
        );
        assertThat(repository.count()).isEqualTo(2);
    }

    private LhAnnouncementCatalogStore store(Instant now) {
        return new LhAnnouncementCatalogStore(repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Entry entry(String panId, String status, String raw) {
        return new Entry(new LhAnnouncementCatalogSnapshot(
                panId, "03", "06", "48", "063", "공고", status,
                "2026.09.25", "20260925", "2026.10.25", "https://apply.lh.or.kr", ""
        ), raw);
    }
}
