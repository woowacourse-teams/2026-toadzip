package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexSourceFixtures;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.correction.dto.IngestCorrectionRequest;
import com.toadzip.backend.ingest.correction.repository.IngestCorrectionRepository;
import com.toadzip.backend.ingest.correction.service.IngestCorrectionService;
import com.toadzip.backend.ingest.correction.service.IngestCorrectionStore;
import com.toadzip.backend.ingest.correction.service.IngestWorkspaceService;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.location.service.RoadAddressGeocodingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IngestCorrectionIntegrationTest {
    private static final String TARGET = "33801:NATIONAL_RENTAL";
    private static final String OTHER = "33802:NATIONAL_RENTAL";
    @Autowired private IngestCorrectionStore store;
    @Autowired private IngestCorrectionService correction;
    @Autowired private IngestWorkspaceService workspace;
    @Autowired private MyHomeComplexSourceFixtures sources;
    @Autowired private MyHomeAnnouncementSourceFixtures announcementSources;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private IngestCorrectionRepository corrections;
    @Autowired private JdbcClient jdbc;
    @Autowired private MockMvc mvc;
    @MockitoBean private RoadAddressGeocodingService geocoding;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE housing_complexes, announcements, myhome_complex_source_regions,
                    myhome_announcement_source_bundles, ingest_corrections, ingest_correction_changes,
                    myhome_complex_mapping_failures, myhome_announcement_mapping_failures,
                    lh_announcement_enrichment_failures CASCADE
                """).update();
        sources.saveAll(List.of(source(33801L, null), source(33802L, "다른 단지")));
    }

    @Test
    void 실패한_보완은_원천을_보존하고_재시도하면_해당_단지만_저장한다() {
        var first = request(TARGET, Map.of("parkngCo", 0));
        assertThatThrownBy(() -> correction.correct("complex", TARGET, first, "admin"))
                .isInstanceOf(InvalidIngestRequestException.class);
        assertThat(complexes.count()).isZero();
        assertThat(corrections.findById("complex:" + TARGET)).isPresent();
        assertThat(workspace.find("complex", "FAILED", 0, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.identifier()).isEqualTo(TARGET));

        long id = correction.correct("complex", TARGET, request(TARGET, Map.of("hsmpNm", "보완한 단지")), "admin");

        assertThat(complexes.findById(id)).get().satisfies(value -> {
            assertThat(value.getName()).isEqualTo("보완한 단지");
            assertThat(value.getParkingSpaceCount()).isZero();
            assertThat(value.isAdminModified()).isTrue();
        });
        assertThat(types.findAll()).singleElement().satisfies(value -> assertThat(value.isAdminCorrection()).isTrue());
        assertThat(sources.findAll().stream().filter(row -> row.getHsmpSn().equals(33801L)))
                .singleElement().satisfies(row -> assertThat(row.getHsmpNm()).isNull());
        assertThat(workspace.find("complex", "READY", 0, 20).items()).hasSize(1);
        assertThat(workspace.find("complex", "WAITING", 0, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.identifier()).isEqualTo(OTHER));
        assertThat(store.detail("complex", TARGET).changes()).hasSize(2);
        assertThat(store.detail("complex", TARGET).managementOnly()).isTrue();
        assertThat(corrections.findById("complex:" + TARGET)).get()
                .satisfies(value -> assertThat(value.getLastFailure()).isNull());
    }

    @Test
    void 수동_등록_단지는_마이홈_식별자가_없어도_기존_수정_화면으로_연결한다() {
        long id = correction.correct("complex", OTHER, request(OTHER, Map.of()), "admin");
        jdbc.sql("DELETE FROM myhome_complex_links WHERE housing_complex_id = ?").param(id).update();
        jdbc.sql("UPDATE housing_complexes SET source_complex_identifier = 'manual-338' WHERE id = ?")
                .param(id).update();

        var detail = store.detail("complex", "manual-338");
        assertThat(detail.productId()).isEqualTo(id);
        assertThat(detail.managementOnly()).isTrue();
        assertThat(detail.rows()).isEmpty();
        assertThat(workspace.find("complex", "READY", 0, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.identifier()).isEqualTo("manual-338"));
    }

    @Test
    void 통합_단지의_다른_원천도_대표_단지의_등록_상태로_조회한다() {
        long id = correction.correct("complex", OTHER, request(OTHER, Map.of()), "admin");
        jdbc.sql("""
                INSERT INTO myhome_complex_links (source_complex_identifier, housing_complex_id, merge_id)
                VALUES (:identifier, :product, :merge)
                """).param("identifier", TARGET).param("product", id)
                .param("merge", java.util.UUID.randomUUID()).update();

        assertThat(workspace.find("complex", "READY", 0, 20).items()).hasSize(2)
                .allSatisfy(item -> assertThat(item.productId()).isEqualTo(id));
        assertThat(store.detail("complex", TARGET).managementOnly()).isTrue();
        assertThat(store.detail("complex", TARGET).productId()).isEqualTo(id);
    }

    @Test
    void 원천이_조회_후_바뀌면_보완_저장을_거절한다() {
        var request = request(TARGET, Map.of("hsmpNm", "보완 이름"));
        jdbc.sql("UPDATE myhome_complex_source_rows SET hsmp_nm = '새 원천' WHERE hsmp_sn = 33801").update();

        assertThatThrownBy(() -> correction.correct("complex", TARGET, request, "admin"))
                .isInstanceOf(AdminDataConflictException.class);
        assertThat(corrections.count()).isZero();
        assertThat(complexes.count()).isZero();
    }

    @Test
    void 다른_대상의_원천과_식별자_변경은_거절한다() {
        var current = store.detail("complex", TARGET);
        var foreign = store.detail("complex", OTHER).rows().getFirst().sourceKey();
        var request = new IngestCorrectionRequest(current.token(),
                List.of(new IngestCorrectionRequest.Row(foreign, Map.of("hsmpNm", "오염"))), null, null);
        assertThatThrownBy(() -> correction.correct("complex", TARGET, request, "admin"))
                .isInstanceOf(InvalidIngestRequestException.class);
        assertThatThrownBy(() -> correction.correct("complex", TARGET,
                request(TARGET, Map.of("hsmpSn", 777L)), "admin"))
                .isInstanceOf(InvalidIngestRequestException.class);
        assertThat(corrections.count()).isZero();
    }

    @Test
    void 단지_매칭_실패시_공고와_공급행_최종_저장은_모두_롤백한다() {
        var snapshot = new com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection
                .MyHomeAnnouncementSourceSnapshot("338-ann", 1, "모집중", "국민임대 입주자 모집공고",
                "서울주택도시공사", "46A", "국민임대", null, "20261001", "20261106", "20261008", "20261015",
                null, "https://example.com/338", null, null, "없는 단지", "인천광역시", "부평구", null,
                null, null, "2823710500105500000", null, null, 0, null, null, null, null);
        var raw = MyHomeAnnouncementSource.from(0, snapshot);
        raw.markCollectedAt(Instant.parse("2026-10-08T00:00:00Z"));
        announcementSources.save(raw);
        var detail = store.detail("announcement", "338-ann");
        var request = new IngestCorrectionRequest(detail.token(),
                List.of(new IngestCorrectionRequest.Row(detail.rows().getFirst().sourceKey(),
                        Map.of("pblancNm", "수정한 국민임대 입주자 모집공고"))), null, null);

        assertThatThrownBy(() -> correction.correct("announcement", "338-ann", request, "admin"))
                .isInstanceOf(InvalidIngestRequestException.class);
        assertThat(announcements.count()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM supply_rows").query(Long.class).single()).isZero();
        assertThat(workspace.find("announcement", "FAILED", 0, 20).items()).hasSize(1);
        assertThat(announcementSources.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getPblancNm()).isEqualTo("국민임대 입주자 모집공고"));
    }

    @Test
    void 조회는_관리자만_가능하고_저장은_CSRF를_검증한다() throws Exception {
        String path = "/api/admin/ingest/workspace/complex";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user("member").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(put(path + "/" + TARGET).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    private IngestCorrectionRequest request(String identifier, Map<String, Object> changes) {
        var detail = store.detail("complex", identifier);
        return new IngestCorrectionRequest(detail.token(),
                List.of(new IngestCorrectionRequest.Row(detail.rows().getFirst().sourceKey(), changes)),
                new BigDecimal("37.518"), new BigDecimal("126.737"));
    }

    private MyHomeComplexSource source(long number, String name) {
        var source = MyHomeComplexSource.from(new MyHomeComplexSourceSnapshot(number,
                "한국토지주택공사", "28", "인천광역시", "237", "부평구", name, "인천광역시 부평구 후정로 33",
                "2823710500105500000", null, 100, "국민임대", "46A", new BigDecimal("46"),
                new BigDecimal("14"), "아파트", null, null, null, 0, null, null, null));
        source.markCollectedAt(Instant.parse("2026-10-08T00:00:00Z"));
        return source;
    }
}
