package com.toadzip.backend.admin.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import jakarta.persistence.EntityManager;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminManagementIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService ownership;

    @org.junit.jupiter.api.BeforeEach
    void isolateOwnershipFromTestTransaction() {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(invocation -> org.mockito.Mockito.mock(
                com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService.Execution.class));
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private EntityManager entityManager;

    @Test
    void 관리자는_단지와_공고_목록을_조회한다() throws Exception {
        mvc.perform(get("/api/admin/housing-complexes").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/announcements").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void 수정과_휴지통_복구는_이력을_남기고_기존_버전의_덮어쓰기를_거부한다() throws Exception {
        long id = createComplex();
        String path = "/api/admin/housing-complexes/" + id;
        var data = (tools.jackson.databind.node.ObjectNode) getData(path).get("data");
        data.put("name", "관리자가 수정한 단지");
        mvc.perform(put(path).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(data.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.modified").value(true));
        mvc.perform(put(path).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(data.toString()))
                .andExpect(status().isConflict());
        long version = getData(path).get("data").get("version").asLong();
        mvc.perform(delete(path).param("version", String.valueOf(version))
                        .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/complexes/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/housing-complexes").param("deleted", "true")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.items[0].id").value(id));
        long deletedVersion = getData(path).get("data").get("version").asLong();
        mvc.perform(post(path + "/restore").param("version", String.valueOf(deletedVersion))
                        .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(path + "/changes").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].action").value("RESTORE"));
    }

    @Test
    void 관리자_수정과_삭제_상태를_재정제가_덮어쓰지_않는다() throws Exception {
        long id = createComplex();
        String path = "/api/admin/housing-complexes/" + id;
        var data = (tools.jackson.databind.node.ObjectNode) getData(path).get("data");
        data.put("name", "확인된 단지명");
        mvc.perform(put(path).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(data.toString())).andExpect(status().isOk());
        var complex = complexes.findById(id).orElseThrow();
        complex.moveToTrash();
        boolean updated = complex.updateFromMyHome("원천 단지명", complex.getSupplyType(), complex.getAddress(),
                10, complex.getProvider(), complex.getCompletionDate(), complex.getHeatingType(),
                complex.getHousingType(), complex.getCorridorType(), complex.getElevatorInstalled(), 10);
        entityManager.flush(); entityManager.clear();
        var persisted = complexes.findById(id).orElseThrow();
        assertThat(updated).isFalse();
        assertThat(persisted.getName()).isEqualTo("확인된 단지명");
        assertThat(persisted.isAdminDeleted()).isTrue();
        assertThat(persisted.isSourceReviewRequired()).isTrue();
    }

    @Test
    void 일반사용자와_CSRF_없는_수정은_거부한다() throws Exception {
        mvc.perform(get("/api/admin/housing-complexes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/housing-complexes").with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/housing-complexes/1").param("version", "0")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/housing-complexes").param("page", "-1")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }

    @Test
    void 검색은_이름_주소_필터와_페이지를_적용한다() throws Exception {
        long id = createComplex(); createComplex();
        String identifier = getData("/api/admin/housing-complexes/" + id).get("sourceIdentifier").asText();
        mvc.perform(get("/api/admin/housing-complexes").param("keyword", "ADMIN_ENTRY")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/admin/housing-complexes").param("keyword", identifier)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(id));
        mvc.perform(get("/api/admin/housing-complexes").param("keyword", "세종대로")
                        .param("provider", "LH").param("rental", "HAPPY_HOUSING").param("size", "1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/api/admin/housing-complexes").param("keyword", "%")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
    }


    @Test
    void 공고_수정과_공급행_수정은_재정제에도_유지되고_단지_삭제를_막는다() throws Exception {
        long complexId = createComplex();
        long announcementId = createAnnouncement(complexId);
        String path = "/api/admin/announcements/" + announcementId;
        var values = (tools.jackson.databind.node.ObjectNode) getData(path).get("data");
        values.put("name", "검토한 모집 공고");
        mvc.perform(put(path).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(values.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.name").value("검토한 모집 공고"));
        var current = getData(path);
        long version = current.get("data").get("version").asLong();
        var supply = current.get("supplyRows").get(0);
        var supplyValues = (tools.jackson.databind.node.ObjectNode) supply.get("data");
        supplyValues.put("totalSupplyHouseholdCount", 17);
        String request = "{\"version\":" + version + ",\"housingComplexId\":" + complexId
                + ",\"supplyRow\":" + supplyValues + "}";
        mvc.perform(put(path + "/supply-rows/" + supply.get("id").asLong())
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.supplyRows[0].modified").value(true))
                .andExpect(jsonPath("$.data.supplyRows[0].data.totalSupplyHouseholdCount").value(17));
        mvc.perform(delete("/api/admin/housing-complexes/" + complexId).param("version", "0")
                        .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isConflict());
        var row = entityManager.find(com.toadzip.backend.announcement.domain.SupplyRow.class,
                supply.get("id").asLong());
        var announcement = row.getAnnouncement();
        assertThat(announcement.enrichFromLh("new-pan", "수집 이유", null)).isFalse();
        assertThat(row.updateFromMyHome(null, null, 0, "원천 단지", "주택형", "111", 
                com.toadzip.backend.announcement.domain.SupplyCategory.NEW_SUPPLY, null, 99)).isFalse();
        assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(17);
        assertThat(row.getHousingComplex().getId()).isEqualTo(complexId);
    }

    @Test
    void 공고_휴지통은_공개_조회에서_제외하고_단지_복구_순서를_보호한다() throws Exception {
        long complexId = createComplex();
        long id = createAnnouncement(complexId);
        String path = "/api/admin/announcements/" + id;
        mvc.perform(delete(path).param("version", "0").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/announcements/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/housing-complexes/" + complexId).param("version", "0")
                .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post(path + "/restore").param("version", "1")
                .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/housing-complexes/" + complexId + "/restore").param("version", "1")
                .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post(path + "/restore").param("version", "1")
                .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/announcements/" + id)).andExpect(status().isOk());
    }

    @Test
    void 접수일정_변경도_버전과_공식_근거를_검증하고_이력을_남긴다() throws Exception {
        long id = createAnnouncement(createComplex());
        String path = "/api/admin/announcements/" + id;
        String request = """
                {"schedules":[{"state":"CONFIRMED","startDate":"2026-09-20","endDate":"2026-09-30",
                "sourceUrl":"https://example.com/notice","sourcePage":3}]}
                """;
        mvc.perform(put(path + "/application-schedules").param("version", "0")
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isNoContent());
        mvc.perform(put(path + "/application-schedules").param("version", "0")
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isConflict());
        mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.schedules[0].sourcePage").value(3));
        mvc.perform(get(path + "/changes").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data[0].action").value("UPDATE_SCHEDULE"));
    }

    private long createAnnouncement(long complexId) throws Exception {
        String request = """
                {"housingComplexId":%d,"name":"관리 테스트 공고","rentalType":"HAPPY_HOUSING",
                "recruitmentType":"NEW","agencyCode":"LH","postedDate":"2026-09-01",
                "applicationStartDate":"2026-09-10","applicationEndDate":"2026-09-20",
                "winnerAnnouncementDate":"2026-10-01","originalUrl":"https://example.com/notice",
                "receptionPlace":{"name":"LH","method":"ONLINE","contact":"1600-1004"},
                "supplyRow":{"sourceComplexName":"테스트 단지","sourceHousingTypeName":"36A",
                "supplyPnu":"1114010100100010000","supplyCategory":"NEW_SUPPLY","totalSupplyHouseholdCount":5}}
                """.formatted(complexId);
        var result = mvc.perform(post("/api/admin/announcements").with(user("admin").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data").get("announcementId").asLong();
    }

    private long createComplex() throws Exception {
        var result = mvc.perform(post("/api/admin/housing-complexes")
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(complexRequest()))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data").get("housingComplexId").asLong();
    }

    private JsonNode getData(String path) throws Exception {
        var result = mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }
    private String complexRequest() {
        return """
                {
                  "name": "두꺼비 행복주택",
                  "rentalType": "HAPPY_HOUSING",
                  "agencyCode": "LH",
                  "address": {
                    "roadAddress": "서울특별시 중구 세종대로 110",
                    "pnu": "1114010100100010000",
                    "legalDongCode": "1114010100",
                    "provinceCode": "11",
                    "cityCountyDistrictCode": "11140",
                    "latitude": 37.566500,
                    "longitude": 126.978000
                  },
                  "totalHouseholdCount": 0,
                  "completionDate": "2020-06-30",
                  "heatingType": "INDIVIDUAL",
                  "buildingType": "APARTMENT",
                  "corridorType": "STAIR",
                  "hasElevator": true,
                  "totalParkingCount": 0,
                  "overviewImageUrl": "https://example.com/complex.png",
                  "moveOutCountLastYear": 0
                }
                """;
    }
}
