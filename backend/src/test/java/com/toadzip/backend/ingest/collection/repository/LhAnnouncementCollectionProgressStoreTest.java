package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectedAtReader;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionProgressStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@Import(LhAnnouncementCollectedAtReader.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LhAnnouncementCollectionProgressStoreTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-25T10:00:00Z");
    private static final ExternalDataSource SUPPLY = ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY;
    private static final String REQUEST = "PAN_ID=100&SPL_INF_TP_CD=063&COLLECTION_VERSION=6";

    @Autowired private LhAnnouncementCollectionLinkRepository linkRepository;
    @Autowired private JdbcClient jdbc;
    @Autowired private LhAnnouncementCollectedAtReader collectedAtReader;

    @Test
    void 같은_요청을_공유하는_공고의_연결을_구분한다() {
        store().complete(SUPPLY, "announcement-100", REQUEST, "100");
        store().link(SUPPLY, "announcement-101", REQUEST, "100");
        assertThat(linkRepository.findAll()).hasSize(2).allSatisfy(link ->
                assertThat(link.matches(REQUEST, "100")).isTrue());
    }

    @Test
    void 재수집_성공은_같은_공고_연결의_완료_시각만_갱신한다() {
        store().complete(SUPPLY, "announcement-100", REQUEST, "100");
        Instant refreshed = COMPLETED_AT.plusSeconds(60);
        store(refreshed).complete(SUPPLY, "announcement-100", REQUEST, "100");
        assertThat(linkRepository.findAll()).singleElement().satisfies(link ->
                assertThat(link.getCompletedAt()).isEqualTo(refreshed));
    }

    @Test
    void 다른_요청에_대한_연결은_현재_요청과_일치하지_않는다() {
        store().complete(SUPPLY, "announcement-100", REQUEST, "100");
        assertThat(linkRepository.findAll()).singleElement().satisfies(link ->
                assertThat(link.matches("PAN_ID=200&SPL_INF_TP_CD=063", "200")).isFalse());
    }

    @Test
    void 연결만_있으면_실제_원천의_최신성을_확인하지_않는다() {
        store().complete(SUPPLY, "source", REQUEST, "100");
        assertThat(hasCollectedAt(REQUEST)).isFalse();
        Instant collected = COMPLETED_AT.minusSeconds(7200);
        canonical(REQUEST, collected);
        assertThat(collectedAtReader.find(SUPPLY, List.of(hash(REQUEST))))
                .containsEntry(hash(REQUEST), collected);
    }

    @Test
    void 다른_수집_버전과_시각이_불명확한_원천을_최신으로_판정하지_않는다() {
        String previous = REQUEST.replace("VERSION=6", "VERSION=5");
        var recordId = canonical(previous, COMPLETED_AT);
        assertThat(hasCollectedAt(REQUEST)).isFalse();
        jdbc.sql("UPDATE lh_announcement_query_sources SET request_hash = :hash, request_description = :request "
                        + "WHERE last_collection_record_id = :record")
                .param("hash", hash(REQUEST)).param("request", REQUEST).param("record", recordId).update();
        assertThat(hasCollectedAt(REQUEST)).isTrue();
        jdbc.sql("UPDATE lh_announcement_query_sources SET collected_at = NULL").update();
        assertThat(hasCollectedAt(REQUEST)).isFalse();
    }

    @Test
    void 다른_공고에_연결해도_성공_원천의_수집_시각은_보존한다() {
        canonical(REQUEST, COMPLETED_AT);
        store(COMPLETED_AT.plusSeconds(60)).link(SUPPLY, "another", REQUEST, "100");
        assertThat(collectedAtReader.find(SUPPLY, List.of(hash(REQUEST))))
                .containsEntry(hash(REQUEST), COMPLETED_AT);
    }

    @Test
    void 성공_원천은_공고_연결을_기록하기_전에도_조회한다() {
        canonical(REQUEST, COMPLETED_AT);
        assertThat(linkRepository.count()).isZero();
        assertThat(hasCollectedAt(REQUEST)).isTrue();
    }

    private boolean hasCollectedAt(String request) {
        return collectedAtReader.find(SUPPLY, List.of(hash(request))).containsKey(hash(request));
    }

    private java.util.UUID canonical(String request, Instant at) {
        var id = java.util.UUID.randomUUID();
        jdbc.sql("INSERT INTO source_collection_records "
                        + "(id, version, source, started_at, finished_at, status, stored_row_count) "
                        + "VALUES (:id, 0, 'LH_ANNOUNCEMENT_SUPPLY', :time, :time, 'SUCCESS', 0)")
                .param("id", id).param("time", java.sql.Timestamp.from(at)).update();
        jdbc.sql("INSERT INTO lh_announcement_query_sources "
                        + "(version, source, pan_id, query_hash, request_hash, request_description, collected_at, "
                        + "verified_empty, last_collection_record_id) VALUES "
                        + "(0, 'LH_ANNOUNCEMENT_SUPPLY', '100', :query, :hash, :request, :time, true, :id)")
                .param("query", hash(request.replaceFirst("&COLLECTION_VERSION=[0-9]+$", "")))
                .param("hash", hash(request)).param("request", request).param("time", java.sql.Timestamp.from(at))
                .param("id", id).update();
        return id;
    }

    private String hash(String request) {
        return LhAnnouncementQuery.requestHashOf(request);
    }

    private LhAnnouncementCollectionProgressStore store() {
        return store(COMPLETED_AT);
    }

    private LhAnnouncementCollectionProgressStore store(Instant completedAt) {
        return new LhAnnouncementCollectionProgressStore(
                linkRepository,
                Clock.fixed(completedAt, ZoneOffset.UTC)
        );
    }
}
