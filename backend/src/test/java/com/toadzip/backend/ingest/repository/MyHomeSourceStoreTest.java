package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import com.toadzip.backend.ingest.dto.MyHomeComplexSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeRegion;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class MyHomeSourceStoreTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-08-25T01:00:00Z");

    @Autowired
    private MyHomeComplexSourceRepository complexRepository;

    @Autowired
    private MyHomeNoticeSourceRepository noticeRepository;

    private MyHomeSourceStore store;

    @BeforeEach
    void setUp() {
        store = new MyHomeSourceStore(
                complexRepository,
                noticeRepository,
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void 마이홈_단지_API_항목을_각각_테이블_행으로_저장한다() {
        MyHomeRegion region = new MyHomeRegion("11", "140", "서울특별시", "중구");
        MyHomeComplexSourceItem first = complex(31845188L, "14", new BigDecimal("14.5400"));
        MyHomeComplexSourceItem second = complex(31845188L, "19", new BigDecimal("19.7000"));

        int storedRowCount = store.replaceComplexRegion(region, List.of(first, second));

        assertThat(storedRowCount).isEqualTo(2);
        assertThat(complexRepository.findAll())
                .hasSize(2)
                .allSatisfy(source -> assertThat(source.getCollectedAt()).isEqualTo(COLLECTED_AT))
                .extracting(source -> source.getStyleNm())
                .containsExactlyInAnyOrder("14", "19");
    }

    @Test
    void 같은_마이홈_공고_식별자는_새_행을_추가하지_않고_컬럼을_갱신한다() {
        store.storeNotices(List.of(notice("공고명")));

        store.storeNotices(List.of(notice("변경 공고명")));

        assertThat(noticeRepository.findAll()).singleElement().satisfies(source -> {
            assertThat(source.getPblancId()).isEqualTo("21026");
            assertThat(source.getHouseSn()).isOne();
            assertThat(source.getPblancNm()).isEqualTo("변경 공고명");
        });
    }

    @Test
    void 새_마이홈_공고의_원천_순서는_기존_행_다음부터_이어진다() {
        store.storeNotices(List.of(notice("첫 공고")));

        MyHomeNoticeSourceItem second = new MyHomeNoticeSourceItem(
                "21027", 2, "일반공고", "두 번째 공고", "부산도시공사", "아파트", "영구임대",
                null, "20260813", "20261106", "20260824", "20260831", null,
                "https://example.com/2", null, null, "동삼2", "부산광역시", "영도구",
                "부산광역시 영도구", null, null, "2620012100105100000", "중앙난방", null,
                300, 2_160_000L, 432_000L, 1_728_000L, 42_800L
        );
        store.storeNotices(List.of(second));

        assertThat(noticeRepository.findAll())
                .extracting(source -> source.getSourceOrder())
                .containsExactlyInAnyOrder(0, 1);
    }

    private MyHomeComplexSourceItem complex(Long hsmpSn, String styleName, BigDecimal exclusiveArea) {
        return new MyHomeComplexSourceItem(
                hsmpSn, "LH서울", "11", "서울특별시", "140", "중구", "서울특별시 중구",
                "서울특별시 중구 퇴계로", "1114016200102510073", null, 1, "매입임대",
                styleName, exclusiveArea, new BigDecimal("10.1800"), "다가구주택", null, null,
                null, 0, 2_777_000L, 316_710L, 0L
        );
    }

    private MyHomeNoticeSourceItem notice(String name) {
        return new MyHomeNoticeSourceItem(
                "21026", 1, "일반공고", name, "부산도시공사", "아파트", "영구임대",
                null, "20260813", "20261106", "20260824", "20260831", null,
                "https://example.com", null, null, "동삼2", "부산광역시", "영도구",
                "부산광역시 영도구", null, null, "2620012100105100000", "중앙난방", null,
                300, 2_160_000L, 432_000L, 1_728_000L, 42_800L
        );
    }
}
