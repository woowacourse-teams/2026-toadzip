package com.toadzip.backend.ingest.lh.collection.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(IngestExecutionExtension.class)
@SpringBootTest(properties = {"spring.main.web-application-type=servlet",
        "ingest.lh-announcement-client.max-concurrent-requests=1"})
@ActiveProfiles("test")
class LhBundleListenerFailureIntegrationTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    @Autowired private LhAnnouncementExternalCollectionService service;
    @Autowired private LhCatalogStorageFixtures catalogStore;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private LhAnnouncementQueryApiRepository externalRepository;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE lh_announcement_catalog_entries, lh_announcement_query_sources,
                    pending_collection_targets CASCADE
                """).update();
    }

    @Test
    void 묶음_후처리가_실패하면_새_묶음을_시작하지_않고_예외를_올린다() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        catalogStore.store(List.of(entry("P-1", today), entry("P-2", today), entry("P-3", today)));
        when(externalRepository.supply(any())).thenReturn(json("""
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsList01":[{"SBD_LGO_NM":"단지","HTY_NNA":"59A","DDO_AR":"59.9800","SPL_AR":"80.0000",
                   "HSH_CNT":"100","NOW_HSH_CNT":"10","LS_GMY":"30000000","MM_RFE":"200000"}]}]
                """));
        when(externalRepository.detail(any())).thenReturn(json("""
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsSbd":[{"LCC_NT_NM":"단지","LGDN_ADR":"경기도 화성시 동탄대로 900","HSH_CNT":"100"}]}]
                """));

        assertThatThrownBy(() -> service.collectBundles(candidates -> {
            throw new IllegalStateException("정제 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("정제 실패");

        // 동시 실행이 1개라 첫 묶음의 후처리가 실패하면 나머지 묶음은 시작하지 않는다.
        verify(externalRepository, times(1)).detail(any());
    }

    private Entry entry(String panId, LocalDate today) {
        return new Entry(new LhAnnouncementCatalogSnapshot(panId, "03", "06", "10", "063", panId + " 행복주택 입주자 모집",
                "공고중", today.format(DATE), today.format(DATE), today.plusDays(10).format(DATE),
                "https://apply.lh.or.kr/" + panId, ""), "{}");
    }

    private JsonNode json(String payload) {
        return JsonMapper.builder().build().readTree(payload);
    }
}
