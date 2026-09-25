package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySourceSnapshot;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.List;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LhSourceStoreQueryCountTest {

    @Autowired
    private LhCatalogSourceRepository catalogRepository;

    @Autowired
    private LhAnnouncementDetailSourceRepository detailRepository;

    @Autowired
    private LhAnnouncementSupplySourceRepository supplyRepository;

    @Autowired
    private LhAnnouncementCollectionCheckpointRepository checkpointRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 같은_공급_원천_100행을_교체할_때_개별_삭제_SQL을_발행하지_않는다() {
        LhSourceStore store = new LhSourceStore(catalogRepository, detailRepository,
                supplyRepository, checkpointRepository, Clock.systemUTC());
        store.replaceSupplies("PROBE", "PROBE-REQUEST", rows());
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        try {
            statistics.setStatisticsEnabled(true);
            statistics.clear();
            store.replaceSupplies("PROBE", "PROBE-REQUEST", rows());
            entityManager.flush();
            assertThat(statistics.getPrepareStatementCount()).isLessThan(120);
        }
        finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
    }

    private List<LhAnnouncementSupplySource> rows() {
        return IntStream.range(0, 100)
                .mapToObj(index -> new LhAnnouncementSupplySource(index, "PROBE",
                        new LhAnnouncementSupplySourceSnapshot(
                                "단지-" + index, "24", "24", "30", "100", "10", null, null)))
                .toList();
    }
}
