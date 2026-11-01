package com.toadzip.backend.ingest.collection.service;

import static com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures.detail;
import static com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures.mainList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.ShAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.ShAnnouncementSourceRepository;
import com.toadzip.backend.ingest.failure.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"ingest.sh.request-interval=0s", "ingest.sh.lookback-days=0"})
@ActiveProfiles("test")
class ShAnnouncementCollectionIntegrationTest {

    @Autowired
    private ShAnnouncementCollectionService service;
    @Autowired
    private ShAnnouncementSourceRepository sources;
    @Autowired
    private ExternalDataCollectionFailureRepository failures;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private ShAnnouncementExternalRepository externalRepository;

    @AfterEach
    void cleanUp() {
        sources.deleteAll();
        failures.deleteAll();
    }

    @Test
    void collectionStoresContentAndParsingFailurePreservesItUntilSuccessfulRetry() {
        String detail = detail();
        when(externalRepository.fetchList(1)).thenReturn(singlePostList());
        when(externalRepository.fetchDetail("100")).thenReturn(detail);
        long announcementCount = count("announcements");
        long housingCount = count("housing_complexes");

        assertThat(service.collect().storedRowCount()).isOne();
        var first = sources.findBySourceKey("SH:100").orElseThrow();
        Long sourceId = first.getId();
        assertThat(first.getBodyText()).contains("입주자 모집 안내", "신청 안내").doesNotContain("<", "\t", "\r");
        assertThat(first.getAttachments()).contains("fileSeq", "공고문");

        when(externalRepository.fetchDetail("100")).thenReturn("<html>변경된 구조</html>");
        assertThat(service.collect().failedRequestCount()).isOne();
        var preserved = sources.findBySourceKey("SH:100").orElseThrow();
        assertThat(preserved.getBodyText()).isEqualTo(first.getBodyText());
        assertThat(preserved.getAttachments()).isEqualTo(first.getAttachments());
        assertThat(preserved.getCollectedAt()).isEqualTo(first.getCollectedAt());
        assertThat(failures.findAllBySourceAndRequestDescriptionAndStatus(
                ExternalDataSource.SH_ANNOUNCEMENT, "seq=100", ExternalDataFailureStatus.PENDING)).hasSize(1);

        doReturn(detail).when(externalRepository).fetchDetail("100");
        assertThat(service.collect().failedRequestCount()).isZero();
        var refreshed = sources.findBySourceKey("SH:100").orElseThrow();
        assertThat(refreshed.getId()).isEqualTo(sourceId);
        assertThat(refreshed.getBodyText()).isEqualTo(first.getBodyText());
        assertThat(failures.findAllBySourceAndRequestDescriptionAndStatus(
                ExternalDataSource.SH_ANNOUNCEMENT, "seq=100", ExternalDataFailureStatus.RESOLVED)).hasSize(1);
        assertThat(count("announcements")).isEqualTo(announcementCount);
        assertThat(count("housing_complexes")).isEqualTo(housingCount);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String singlePostList() {
        var document = Jsoup.parse(mainList());
        document.select("#listTb tbody > tr").get(1).remove();
        document.selectFirst("#mainform .mentcount strong").text("1");
        return document.html();
    }
}
