package com.toadzip.backend.ingest.collection.history.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogSourceReader;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhAnnouncementDetailSourceReader;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogSourceReader;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhAnnouncementSupplySourceReader;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SourceProjectionIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MyHomeComplexSourceReader complexes;
    @Autowired private MyHomeAnnouncementSourceReader announcements;
    @Autowired private LhLeaseCatalogSourceReader leases;
    @Autowired private LhAnnouncementCatalogSourceReader catalogs;
    @Autowired private LhAnnouncementSupplySourceReader supplies;
    @Autowired private LhAnnouncementDetailSourceReader details;

    @Test
    void announcementProjectionDeduplicatesEqualRowsPreservesLifecycleAndUsesCanonicalCursor() {
        UUID record = record("MYHOME_ANNOUNCEMENT", "IMPORTED");
        long parent = jdbc.queryForObject("INSERT INTO myhome_announcement_source_bundles(version, pblanc_id, "
                + "last_collection_record_id) VALUES (0, 'PROJECTION-A', ?) RETURNING id", Long.class, record);
        for (int order = 0; order < 2; order++) {
            jdbc.update("INSERT INTO myhome_announcement_source_rows(source_id, collection_record_id, "
                    + "request_supply_type_code, source_order, pblanc_id, house_sn, pblanc_nm, active, "
                    + "consecutive_miss_count, last_seen_run_id) VALUES (?, ?, NULL, ?, 'PROJECTION-A', 1, "
                    + "'새 공고', false, 2, 'imported-old-run')", parent, record, order);
        }

        assertThat(announcements.findAllByPblancIdOrderByIdAsc("PROJECTION-A")).singleElement().satisfies(row -> {
            assertThat(row.getPblancNm()).isEqualTo("새 공고");
            assertThat(row.getCollectedAt()).isNull();
            assertThat(row.getLastSeenRunId()).isEqualTo("imported-old-run");
            assertThat(row.isActive()).isFalse();
            assertThat(row.getConsecutiveMissCount()).isEqualTo(2);
        });
        assertThat(announcements.findByIdGreaterThanOrderByIdAsc(0L, PageRequest.of(0, 100)))
                .filteredOn(row -> "PROJECTION-A".equals(row.getPblancId()))
                .allSatisfy(row -> assertThat(row.getPblancNm()).isEqualTo("새 공고"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM myhome_announcement_source_rows WHERE source_id = ?",
                Integer.class, parent)).isEqualTo(2);
    }

    @Test
    void sameHouseUsesCurrentObservationWhileOtherHistoricalHousesRemainAvailable() {
        UUID record = record("MYHOME_ANNOUNCEMENT", "IMPORTED");
        long parent = jdbc.queryForObject("INSERT INTO myhome_announcement_source_bundles(version, pblanc_id, "
                + "last_collection_record_id) VALUES (0, 'HOUSE-HISTORY', ?) RETURNING id", Long.class, record);
        jdbc.update("INSERT INTO myhome_announcement_source_rows(source_id, collection_record_id, "
                + "source_order, pblanc_id, house_sn, pblanc_nm, active, consecutive_miss_count, last_seen_run_id) "
                + "VALUES (?, ?, 0, 'HOUSE-HISTORY', 1, '이전 관찰', true, 0, 'old')", parent, record);
        jdbc.update("INSERT INTO myhome_announcement_source_rows(source_id, collection_record_id, "
                + "request_supply_type_code, collected_at, source_order, pblanc_id, house_sn, pblanc_nm, active, "
                + "consecutive_miss_count, last_seen_run_id) VALUES "
                + "(?, ?, '01', now(), 1, 'HOUSE-HISTORY', 1, '현재 관찰', true, 0, 'current')", parent, record);
        jdbc.update("INSERT INTO myhome_announcement_source_rows(source_id, collection_record_id, "
                + "source_order, pblanc_id, house_sn, pblanc_nm, active, consecutive_miss_count, last_seen_run_id) "
                + "VALUES (?, ?, 2, 'HOUSE-HISTORY', 2, '과거 주택', false, 2, 'old')", parent, record);

        assertThat(announcements.findAllByPblancIdOrderByIdAsc("HOUSE-HISTORY"))
                .extracting(row -> row.getPblancNm()).containsExactlyInAnyOrder("현재 관찰", "과거 주택");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM myhome_announcement_source_rows WHERE source_id = ?",
                Integer.class, parent)).isEqualTo(3);
    }

    @Test
    void canonicalQueryVersionSuppressesOldRowsAndVerifiedEmptyKeepsItsMeaning() {
        String query = "PAN_ID=PROJECTION-PAN&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06&SPL_INF_TP_CD=062";
        String old = query + "&COLLECTION_VERSION=6";
        String current = query + "&COLLECTION_VERSION=7";
        String oldHash = LhAnnouncementQuery.requestHashOf(old);
        String hash = LhAnnouncementQuery.requestHashOf(current);
        UUID record = record("LH_ANNOUNCEMENT_SUPPLY", "SUCCESS");
        jdbc.update("INSERT INTO lh_announcement_query_sources(version, source, pan_id, query_hash, request_hash, "
                + "request_description, collected_at, verified_empty, last_collection_record_id) "
                + "VALUES (0, 'LH_ANNOUNCEMENT_SUPPLY', 'PROJECTION-PAN', ?, ?, ?, now(), true, ?)",
                LhAnnouncementQuery.requestHashOf(query), hash, current, record);

        assertThat(supplies.findAllByPanIdAndRequestHashOrderBySourceOrderAsc("PROJECTION-PAN", oldHash)).isEmpty();
        assertThat(supplies.hasVerifiedEmptySupplies("PROJECTION-PAN", current)).isTrue();
        assertThat(supplies.hasVerifiedEmptySupplies("PROJECTION-PAN", old)).isFalse();
    }

    @Test
    void detailProjectionPreservesAllScheduleFieldsAndUnknownActualTime() {
        String query = "PAN_ID=DETAIL-PAN&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06&SPL_INF_TP_CD=062";
        String description = query + "&COLLECTION_VERSION=6";
        String hash = LhAnnouncementQuery.requestHashOf(description);
        UUID record = record("LH_ANNOUNCEMENT_DETAIL", "IMPORTED");
        long parent = jdbc.queryForObject("INSERT INTO lh_announcement_query_sources(version, source, pan_id, "
                + "query_hash, request_hash, request_description, verified_empty, last_collection_record_id) "
                + "VALUES (0, 'LH_ANNOUNCEMENT_DETAIL', 'DETAIL-PAN', ?, ?, ?, false, ?) RETURNING id", Long.class,
                LhAnnouncementQuery.requestHashOf(query), hash, description, record);
        jdbc.update("INSERT INTO lh_announcement_detail_rows(source_id, source_order, dataset_type, "
                + "application_begin_date, application_end_date, winner_announcement_date) "
                + "VALUES (?, 0, 'SCHEDULE', '20261005', '20261007', '20261009')", parent);

        assertThat(details.findAllByPanIdAndRequestHashOrderBySourceOrderAsc("DETAIL-PAN", hash))
                .singleElement().satisfies(row -> {
                    assertThat(row.getApplicationBeginDate()).isEqualTo("20261005");
                    assertThat(row.getApplicationEndDate()).isEqualTo("20261007");
                    assertThat(row.getWinnerAnnouncementDate()).isEqualTo("20261009");
                    assertThat(row.getCollectedAt()).isNull();
                });
    }

    private UUID record(String source, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO source_collection_records(id, version, source, started_at, finished_at, status, "
                + "stored_row_count) VALUES (?, 0, ?, now(), now(), ?, 0)", id, source, status);
        return id;
    }
}
