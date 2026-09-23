package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.domain.LhCatalogSourceSnapshot;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LhSourceStoreTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-08-25T01:00:00Z");

    @Autowired
    private LhCatalogSourceRepository catalogRepository;

    @Autowired
    private LhAnnouncementDetailSourceRepository detailRepository;

    @Autowired
    private LhAnnouncementSupplySourceRepository supplyRepository;

    private LhSourceStore store;

    @BeforeEach
    void setUp() {
        store = new LhSourceStore(
                catalogRepository,
                detailRepository,
                supplyRepository,
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void LH_카탈로그_응답_항목을_각각_테이블_행으로_저장한다() {
        List<LhCatalogSourceSnapshot> snapshots = List.of(
                catalog("강릉교동 행복주택", "36.97"),
                catalog("강릉교동 행복주택", "44.12")
        );

        int storedRowCount = store.replaceCatalog(snapshots);

        assertThat(storedRowCount).isEqualTo(2);
        assertThat(catalogRepository.findAll())
                .allSatisfy(source -> assertThat(source.getCollectedAt()).isEqualTo(COLLECTED_AT))
                .extracting(source -> source.getExclusiveArea())
                .containsExactly("36.97", "44.12");
    }

    @Test
    void 빈_LH_카탈로그_응답은_기존_snapshot을_삭제하지_않는다() {
        store.replaceCatalog(List.of(catalog("강릉교동 행복주택", "36.97")));

        assertThatThrownBy(() -> store.replaceCatalog(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catalogRepository.count()).isOne();
    }

    @Test
    void LH_상세와_공급은_panId별로_각각_독립된_테이블에_저장한다() {
        LhAnnouncementDetailSource detail = new LhAnnouncementDetailSource(
                0, "PAN-1", "ETC_INFO", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "정정", "공고문 확인"
        );
        LhAnnouncementSupplySource supply = new LhAnnouncementSupplySource(
                0,
                "PAN-1",
                new LhAnnouncementSupplySourceSnapshot(
                        "순천선평3",
                        "24(일반)",
                        "24.71",
                        "37.9268",
                        "240",
                        "50",
                        null,
                        null
                )
        );

        store.replaceDetails("PAN-1", "PAN_ID=PAN-1&TYPE=DETAIL", List.of(detail));
        store.replaceSupplies("PAN-1", "PAN_ID=PAN-1&TYPE=SUPPLY", List.of(supply));

        assertThat(detailRepository.findAll()).singleElement()
                .extracting(source -> source.getDatasetType())
                .isEqualTo("ETC_INFO");
        assertThat(supplyRepository.findAll()).singleElement()
                .extracting(source -> source.getComplexLabel())
                .isEqualTo("순천선평3");
    }

    @Test
    void 같은_panId의_다른_조회_조건이_기존_원천을_덮지_않는다() {
        LhAnnouncementSupplySource first = new LhAnnouncementSupplySource(
                0, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "첫 번째 단지", "24", "24", "30", "10", "5", null, null
                )
        );
        LhAnnouncementSupplySource second = new LhAnnouncementSupplySource(
                0, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "두 번째 단지", "36", "36", "45", "20", "8", null, null
                )
        );

        store.replaceSupplies("PAN-1", "PAN_ID=PAN-1&TYPE=A", List.of(first));
        store.replaceSupplies("PAN-1", "PAN_ID=PAN-1&TYPE=B", List.of(second));
        store.replaceSupplies("PAN-1", "PAN_ID=PAN-1&TYPE=A", List.of(new LhAnnouncementSupplySource(
                0, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "수정된 첫 번째 단지", "24", "24", "30", "10", "5", null, null
                )
        )));

        assertThat(supplyRepository.findAll()).hasSize(2);
        assertThat(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "PAN-1", LhAnnouncementCollectionCheckpoint.requestHashOf("PAN_ID=PAN-1&TYPE=A")
        )).singleElement().extracting(LhAnnouncementSupplySource::getComplexLabel)
                .isEqualTo("수정된 첫 번째 단지");
        assertThat(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "PAN-1", LhAnnouncementCollectionCheckpoint.requestHashOf("PAN_ID=PAN-1&TYPE=B")
        )).singleElement().extracting(LhAnnouncementSupplySource::getComplexLabel).isEqualTo("두 번째 단지");
    }

    @Test
    void 응답의_panId가_요청과_다르면_기존_원천을_보존한다() {
        String request = "PAN_ID=PAN-1&TYPE=A";
        store.replaceSupplies("PAN-1", request, List.of(new LhAnnouncementSupplySource(
                0, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "기존 단지", "24", "24", "30", "10", "5", null, null
                )
        )));

        assertThatThrownBy(() -> store.replaceSupplies("PAN-1", request, List.of(
                new LhAnnouncementSupplySource(0, "PAN-2", new LhAnnouncementSupplySourceSnapshot(
                        "잘못된 단지", "36", "36", "45", "20", "8", null, null
                ))
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThat(supplyRepository.findAll()).singleElement()
                .extracting(LhAnnouncementSupplySource::getComplexLabel).isEqualTo("기존 단지");
    }

    private LhCatalogSourceSnapshot catalog(String label, String area) {
        return new LhCatalogSourceSnapshot(
                "강원특별자치도 강릉시", "행복주택", label, "180", area, "72", "0", "0"
        );
    }
}
