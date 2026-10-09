package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Request;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnouncementSupplyMatchingIntegrationTest {
    private static final String PATH = "/api/admin/ingest/announcement-supply-matches";
    private static final String TARGET = "manual-21395";
    private static final String PNU = "2823710500105500000";
    @Autowired private AnnouncementSupplyMatchingService matching;
    @Autowired private MyHomeAnnouncementSourceFixtures sources;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private SupplyRowRepository supplies;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JdbcClient jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private LhAnnouncementCollectionCandidateResolver candidateResolver;
    @Autowired private LhAnnouncementCollectionProgressStore progressStore;
    private HousingComplex complex;
    private HousingType type;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE housing_complexes, announcements, myhome_announcement_source_bundles,
                    myhome_announcement_mapping_failures CASCADE
                """).update();
        complex = complex("한스빌아파트", "complex-21395", PNU, "NATIONAL_RENTAL");
        type = types.save(HousingType.createFromMyHome(complex, "complex-21395:26", "26",
                new BigDecimal("26.0000"), new BigDecimal("40.0000")));
        sources.save(source(TARGET, 1, PNU));
    }

    @Test
    void 선택값을_한번에_보내면_별도_저장_없이_공고가_등록된다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        assertThat(row.complexId()).isEqualTo(complex.getId());
        assertThat(row.housingTypeId()).isNull();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isOk());
        assertThat(supplies.findAll()).singleElement().satisfies(value -> {
            assertThat(value.getHousingType().getId()).isEqualTo(type.getId());
            assertThat(value.getSourceHousingTypeName()).isEqualTo("26A,B");
        });
        assertThat(sources.findAll()).singleElement().satisfies(value ->
                assertThat(value.getHsmpNm()).isEqualTo("익산한스빌"));
        assertThat(complexes.findById(complex.getId()).orElseThrow().getName()).isEqualTo("한스빌아파트");
        // 선택 규칙은 남지 않는다. 자동 매칭 결과는 여전히 모호하지만 최종 공급행의 연결은 저장된다.
        assertThat(matching.rows(TARGET).getFirst().housingTypeId()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name IN ('announcement_supply_matches', 'ingest_corrections', 'ingest_correction_changes')")
                .query(Long.class).single()).isZero();
    }

    @Test
    void 여러_공급행을_한번에_선택하고_다른_공고의_원천은_변경하지_않는다() throws Exception {
        sources.save(source(TARGET, 2, PNU));
        sources.save(source("other-ann", 1, PNU));
        var requests = matching.rows(TARGET).stream()
                .map(row -> request(row, complex.getId(), type.getId())).toList();
        refine(TARGET, requests).andExpect(status().isOk());
        assertThat(supplies.count()).isEqualTo(2);
        assertThat(announcements.findBySourceAnnouncementIdentifier("other-ann")).isEmpty();
        assertThat(sources.findAll()).hasSize(3);
    }

    @Test
    void 일부_행이_유효하지_않으면_어떤_공고나_공급행도_저장하지_않는다() throws Exception {
        sources.save(source(TARGET, 2, PNU));
        var rows = matching.rows(TARGET);
        var other = complex("다른 단지", "other", PNU, "NATIONAL_RENTAL");
        var foreign = types.save(HousingType.createFromMyHome(other, "other:26", "26", BigDecimal.ONE, null));
        refine(TARGET, List.of(request(rows.getFirst(), complex.getId(), type.getId()),
                request(rows.getLast(), complex.getId(), foreign.getId()))).andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
        assertThat(supplies.count()).isZero();
    }

    @Test
    void 정제_후_LH_보강이_실패하면_공고와_공급행을_모두_롤백한다() throws Exception {
        var source = sources.findAll().getFirst();
        ReflectionTestUtils.setField(source, "suplyInsttNm", "LH");
        ReflectionTestUtils.setField(source, "url", "https://example.com/announcements?panId=manual-lh"
                + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07");
        sources.save(source);
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        for (var target : List.of(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)) {
            progressStore.complete(target, TARGET, candidate.requestDescription(), candidate.panId());
        }
        var row = matching.rows(TARGET).getFirst();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
        assertThat(supplies.count()).isZero();
        assertThat(sources.findAll()).hasSize(1);
    }

    @Test
    void 정제_실패_후_원천을_유지하고_선택을_고쳐_재시도할_수_있다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        refine(TARGET, List.of(request(row, complex.getId(), null))).andExpect(status().isBadRequest());
        assertThat(supplies.count()).isZero();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isOk());
        assertThat(supplies.count()).isEqualTo(1);
    }

    @Test
    void PNU가_달라도_관리자가_명시한_단지와_주택형으로_정제한다() throws Exception {
        jdbc.sql("UPDATE myhome_announcement_source_rows SET pnu = '1111010100100010000'").update();
        var row = matching.rows(TARGET).getFirst();
        assertThat(row.complexId()).isNull();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isOk());
        assertThat(supplies.findAll().getFirst().getHousingComplex().getId()).isEqualTo(complex.getId());
    }

    @Test
    void 조회_후_원천이_변경되면_정제를_거절한다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        jdbc.sql("UPDATE myhome_announcement_source_rows SET house_ty_nm = '새 주택형'").update();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isConflict());
        assertThat(announcements.count()).isZero();
    }

    @Test
    void 재수집_시각만_변경되면_기존_조회_값으로_정제할_수_있다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        jdbc.sql("UPDATE myhome_announcement_source_rows SET collected_at = now()").update();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isOk());
    }

    @Test
    void 새_공급행이_추가되면_다시_조회하도록_한다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        sources.save(source(TARGET, 2, PNU));
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isConflict());
        assertThat(announcements.count()).isZero();
    }

    @Test
    void 다른_공고의_행이나_중복_행은_거절한다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        sources.save(source("other-ann", 1, PNU));
        refine("other-ann", List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isBadRequest());
        sources.save(source(TARGET, 2, PNU));
        var selection = request(row, complex.getId(), type.getId());
        refine(TARGET, List.of(selection, selection)).andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
    }

    @Test
    void 삭제되었거나_공급유형이_다른_단지는_거절한다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        jdbc.sql("UPDATE housing_complexes SET admin_deleted = true WHERE id = ?").param(complex.getId()).update();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isBadRequest());
        jdbc.sql("UPDATE housing_complexes SET admin_deleted = false, supply_type = 'HAPPY_HOUSING' WHERE id = ?")
                .param(complex.getId()).update();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
    }

    @Test
    void 기존_공고도_정제하며_반복_요청은_중복_생성을_하지_않는다() throws Exception {
        var requests = List.of(request(matching.rows(TARGET).getFirst(), complex.getId(), type.getId()));
        refine(TARGET, requests).andExpect(status().isOk());
        refine(TARGET, requests).andExpect(status().isOk());
        assertThat(announcements.count()).isEqualTo(1);
        assertThat(supplies.count()).isEqualTo(1);
    }

    @Test
    void 관리자가_수정한_공급행은_덮어쓰지_않는다() throws Exception {
        var requests = List.of(request(matching.rows(TARGET).getFirst(), complex.getId(), type.getId()));
        refine(TARGET, requests).andExpect(status().isOk());
        jdbc.sql("UPDATE supply_rows SET admin_modified = true").update();
        jdbc.sql("UPDATE myhome_announcement_source_rows SET pblanc_nm = '변경된 공고명'").update();
        var current = List.of(request(matching.rows(TARGET).getFirst(), complex.getId(), type.getId()));
        refine(TARGET, current).andExpect(status().isBadRequest());
        assertThat(supplies.findAll().getFirst().getHousingType().getId()).isEqualTo(type.getId());
        assertThat(announcements.findAll().getFirst().getName()).isEqualTo("국민임대 입주자 모집공고");
    }

    @Test
    void 관리자가_공고_기관을_변경했다면_선택을_무시하고_성공하지_않는다() throws Exception {
        var requests = List.of(request(matching.rows(TARGET).getFirst(), complex.getId(), type.getId()));
        refine(TARGET, requests).andExpect(status().isOk());
        jdbc.sql("UPDATE announcements SET admin_modified = true, provider = 'LH'").update();
        refine(TARGET, requests).andExpect(status().isBadRequest());
        assertThat(supplies.count()).isEqualTo(1);
        assertThat(supplies.findAll().getFirst().getHousingType().getId()).isEqualTo(type.getId());
    }

    @Test
    void 단지_검색과_주택형_조회는_해당_단지의_기존_데이터만_반환한다() {
        assertThat(matching.complexes("한스빌")).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo(complex.getId()));
        assertThat(matching.housingTypes(complex.getId())).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo(type.getId()));
    }

    @Test
    void 조회와_정제는_관리자_인증_CSRF_필수값을_검증한다() throws Exception {
        mvc.perform(get(PATH + "/" + TARGET)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/" + TARGET).with(user("member").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user("member").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}")).andExpect(status().isBadRequest());
    }

    private ResultActions refine(String identifier, List<Request> requests) throws Exception {
        return mvc.perform(post(PATH + "/" + identifier + "/refine").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rows", requests))));
    }

    private Request request(Row row, long complexId, Long housingTypeId) {
        return new Request(row.rowIdentifier(), row.token(), complexId, housingTypeId);
    }

    private HousingComplex complex(String name, String identifier, String pnu, String supplyType) {
        return complexes.save(HousingComplex.createFromMyHome(name, identifier, supplyType,
                Address.create("익산시 목천로 30-14", pnu, pnu.substring(0, 10), "28", "28237",
                        new BigDecimal("35.9"), new BigDecimal("126.9")),
                100, "SH", null, "INDIVIDUAL", "APARTMENT", "CORRIDOR", true, 80));
    }

    private MyHomeAnnouncementSource source(String identifier, int house, String pnu) {
        var source = MyHomeAnnouncementSource.from(house, new MyHomeAnnouncementSourceSnapshot(identifier,
                house, "모집중", "국민임대 입주자 모집공고", "서울주택도시공사", "26A,B", "국민임대", null,
                "20261001", "20261106", "20261008", "20261015", null, "https://example.com/manual", null,
                null, "익산한스빌", "전북특별자치도", "익산시", null, null, null, pnu, null, null,
                0, null, null, null, null));
        source.markCollectedAt(Instant.parse("2026-10-08T00:00:00Z"));
        return source;
    }
}
