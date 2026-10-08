package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.housing.dto.response.HousingMapIndividualNodeResponse;
import com.toadzip.backend.housing.repository.ComplexSummaryRow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class HousingMapMarkerStatusTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"BEFORE_APPLICATION", "APPLYING", "CLOSED", "CONDITIONAL"})
    void 지도_응답은_조회한_대표_공고_상태를_보존한다(String applicationStatus) {
        ComplexSummaryRow row = new ComplexSummaryRow(
                17L, "행복 단지", null, "11", "11140", "행복주택", "LH",
                null, null, null, null, null, null, null, null,
                1L, null, null, null, null, null, applicationStatus, null
        );
        HousingComplexSummaryMapper mapper = new HousingComplexSummaryMapper(new HousingComplexCodeMapper());
        HousingMapIndividualNodeResponse node = new HousingMapIndividualNodeResponse(mapper.toMapItem(row));

        String json = JsonMapper.builder().build().writeValueAsString(node);
        if (applicationStatus == null) {
            assertTrue(json.contains("\"applicationStatus\":null"));
            return;
        }
        assertTrue(json.contains("\"applicationStatus\":\"" + applicationStatus + "\""));
    }
}
