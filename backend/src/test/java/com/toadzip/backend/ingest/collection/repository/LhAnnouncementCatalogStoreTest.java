package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementCatalogPage.Entry;
import jakarta.persistence.EntityManager;
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

    @Autowired
    private EntityManager entityManager;

    @Test
    void 같은_목록을_다시_관찰해도_변경시각을_갱신하지_않는다() {
        var first = store(FIRST).store(List.of(entry("100", "공고중", "original")));
        var second = store(FIRST.plusSeconds(60)).store(List.of(
                entry("100", "공고중", "different RNUM and ALL_CNT")
        ));

        assertThat(first.newRowCount()).isOne();
        assertThat(first.changedRowCount()).isZero();
        assertThat(second.newRowCount()).isZero();
        assertThat(second.changedRowCount()).isZero();
        assertThat(second.unchangedRowCount()).isOne();

        assertThat(repository.findAll()).singleElement().satisfies(source -> {
            assertThat(source.getChangedAt()).isEqualTo(FIRST);
            assertThat(source.getCollectedAt()).isEqualTo(FIRST.plusSeconds(60));
        });
    }

    @Test
    void 공고상태가_바뀌면_변경시각을_갱신한다() {
        store(FIRST).store(List.of(entry("100", "공고중", "{}")));
        var result = store(FIRST.plusSeconds(60)).store(List.of(entry("100", "정정공고중", "{}")));

        assertThat(result.newRowCount()).isZero();
        assertThat(result.changedRowCount()).isOne();
        assertThat(result.unchangedRowCount()).isZero();

        assertThat(repository.findAll()).singleElement().satisfies(source ->
                assertThat(source.getChangedAt()).isEqualTo(FIRST.plusSeconds(60))
        );
    }

    @Test
    void 현재_검색범위에_없는_공고_원천을_삭제하지_않는다() {
        store(FIRST).store(List.of(entry("100", "공고중", "{}"), entry("200", "공고중", "{}")));
        var result = store(FIRST.plusSeconds(60)).store(List.of(entry("200", "공고중", "{}")));

        assertThat(result.storedRowCount()).isOne();
        assertThat(result.unchangedRowCount()).isOne();

        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findAllByPanIdIn(List.of("100"))).singleElement().satisfies(source -> {
            assertThat(source.getCollectedAt()).isEqualTo(FIRST);
            assertThat(source.isPresentInLatestCatalog()).isFalse();
        });
        assertThat(repository.findAllByPanIdIn(List.of("200"))).singleElement().satisfies(source ->
                assertThat(source.isPresentInLatestCatalog()).isTrue()
        );
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void 조회_유형이_바뀌면_이전_행은_보존하지만_현재_목록에서는_제외한다() {
        store(FIRST).store(List.of(entry("100", "48", "공고중", "{}")));
        store(FIRST.plusSeconds(60)).store(List.of(entry("100", "10", "공고중", "{}")));

        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findAllByPanIdIn(List.of("100"))).hasSize(2);
        assertThat(repository.findAllByPanIdInAndPresentInLatestCatalogTrue(List.of("100")))
                .singleElement().satisfies(source ->
                        assertThat(source.getAnnouncementTypeCode()).isEqualTo("10")
                );
    }

    @Test
    void 검증되지_않은_빈_목록은_기존_행의_포함_상태를_보존한다() {
        store(FIRST).store(List.of(entry("100", "공고중", "{}")));

        assertThatThrownBy(() -> store(FIRST.plusSeconds(60)).store(List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        entityManager.flush();
        entityManager.clear();
        assertThat(repository.count()).isOne();
        assertThat(repository.findAllByPanIdInAndPresentInLatestCatalogTrue(List.of("100"))).hasSize(1);
    }

    @Test
    void 사라졌다가_다시_나타난_행은_변경으로_판정한다() {
        store(FIRST).store(List.of(entry("100", "공고중", "{}")));
        store(FIRST.plusSeconds(60)).store(List.of(entry("200", "공고중", "{}")));
        entityManager.flush();
        entityManager.clear();

        var result = store(FIRST.plusSeconds(120)).store(List.of(entry("100", "공고중", "{}")));

        assertThat(result.changedRowCount()).isOne();
        assertThat(repository.findAllByPanIdInAndPresentInLatestCatalogTrue(List.of("100")))
                .singleElement().satisfies(source ->
                        assertThat(source.getChangedAt()).isEqualTo(FIRST.plusSeconds(120))
                );
    }

    @Test
    void 한_실행의_신규_변경_동일_행을_구분한다() {
        store(FIRST).store(List.of(entry("100", "공고중", "{}"), entry("200", "공고중", "{}")));

        var result = store(FIRST.plusSeconds(60)).store(List.of(
                entry("100", "정정공고중", "{}"), entry("200", "공고중", "{}"), entry("300", "공고중", "{}")
        ));

        assertThat(result.storedRowCount()).isEqualTo(3);
        assertThat(result.newRowCount()).isOne();
        assertThat(result.changedRowCount()).isOne();
        assertThat(result.unchangedRowCount()).isOne();
    }

    private LhAnnouncementCatalogStore store(Instant now) {
        return new LhAnnouncementCatalogStore(repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Entry entry(String panId, String status, String raw) {
        return entry(panId, "48", status, raw);
    }

    private Entry entry(String panId, String announcementTypeCode, String status, String raw) {
        return new Entry(new LhAnnouncementCatalogSnapshot(
                panId, "03", "06", announcementTypeCode, "063", "공고", status,
                "2026.09.25", "20260925", "2026.10.25", "https://apply.lh.or.kr", ""
        ), raw);
    }
}
