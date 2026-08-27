package com.toadzip.backend.global.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.web-application-type=servlet"
)
@ActiveProfiles({"local", "test"})
class OpenApiDocumentationIntegrationTest {

    private static final Set<String> COMMON_SEARCH_PARAMETERS = Set.of(
            "keyword",
            "regionCode",
            "rentalTypes",
            "applicationStatuses",
            "agencyCodes",
            "recruitmentTypes",
            "minDeposit",
            "maxDeposit",
            "minMonthlyRent",
            "maxMonthlyRent",
            "minExclusiveArea",
            "maxExclusiveArea",
            "builtYearFrom",
            "builtYearTo",
            "hasElevator",
            "southWestLat",
            "southWestLng",
            "northEastLat",
            "northEastLng"
    );

    private static final Set<String> LIST_ONLY_PARAMETERS = Set.of("sort", "cursor", "size");

    @LocalServerPort
    private int port;

    @Test
    void 로컬_프로필은_헬스_체크_API가_포함된_OpenAPI_문서를_제공한다() throws Exception {
        HttpResponse<String> response = TestHttpClient.get(port, "/v3/api-docs");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
        assertNotNull(JsonPath.read(response.body(), "$.paths['/api/health'].get"));
    }

    @Test
    void 로컬_프로필은_Swagger_UI를_제공한다() throws Exception {
        HttpResponse<String> response = TestHttpClient.get(port, "/swagger-ui/index.html");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("text/html"));
    }

    @Test
    void OpenAPI_문서는_서비스_기본_정보를_제공한다() throws Exception {
        HttpResponse<String> response = TestHttpClient.get(port, "/v3/api-docs");

        assertEquals(200, response.statusCode());
        assertEquals("두꺼비집 API", JsonPath.read(response.body(), "$.info.title"));
        assertEquals("0.0.1", JsonPath.read(response.body(), "$.info.version"));
    }

    @Test
    void 로컬_프로필_OpenAPI는_단지_목록_지도_상세_GET_경로를_제공한다() throws Exception {
        HttpResponse<String> response = TestHttpClient.get(port, "/v3/api-docs");

        assertEquals(200, response.statusCode());
        assertAll(
                () -> assertNotNull(JsonPath.read(response.body(), "$.paths['/api/v1/complexes'].get")),
                () -> assertNotNull(JsonPath.read(response.body(), "$.paths['/api/v1/complexes/map'].get")),
                () -> assertNotNull(JsonPath.read(
                        response.body(),
                        "$.paths['/api/v1/complexes/{complexId}'].get"
                ))
        );
    }

    @Test
    void 단지_OpenAPI는_지도_경계를_필수_query_parameter로_제공한다() throws Exception {
        HttpResponse<String> response = TestHttpClient.get(port, "/v3/api-docs");

        assertEquals(200, response.statusCode());
        assertAll(
                () -> assertRequiredQueryParameter(response.body(), "/api/v1/complexes", "southWestLat"),
                () -> assertRequiredQueryParameter(response.body(), "/api/v1/complexes", "southWestLng"),
                () -> assertRequiredQueryParameter(response.body(), "/api/v1/complexes", "northEastLat"),
                () -> assertRequiredQueryParameter(response.body(), "/api/v1/complexes", "northEastLng"),
                () -> assertRequiredQueryParameter(response.body(), "/api/v1/complexes/map", "southWestLat"),
                () -> assertRequiredQueryParameter(response.body(), "/api/v1/complexes/map", "southWestLng"),
                () -> assertRequiredQueryParameter(response.body(), "/api/v1/complexes/map", "northEastLat"),
                () -> assertRequiredQueryParameter(response.body(), "/api/v1/complexes/map", "northEastLng")
        );
    }

    @Test
    void 단지_목록과_지도_OpenAPI는_모든_공통_검색_parameter를_제공한다() throws Exception {
        HttpResponse<String> response = TestHttpClient.get(port, "/v3/api-docs");

        assertEquals(200, response.statusCode());
        Set<String> expectedListParameters = new HashSet<>(COMMON_SEARCH_PARAMETERS);
        expectedListParameters.addAll(LIST_ONLY_PARAMETERS);
        List<Map<String, Object>> listParameters = parameters(response.body(), "/api/v1/complexes");
        List<Map<String, Object>> mapParameters = parameters(response.body(), "/api/v1/complexes/map");
        assertAll(
                () -> assertExactQueryParameters(listParameters, expectedListParameters),
                () -> assertExactQueryParameters(mapParameters, COMMON_SEARCH_PARAMETERS)
        );
    }

    @Test
    void 단지_목록_OpenAPI만_다섯_정렬과_cursor_size_계약을_제공한다() throws Exception {
        HttpResponse<String> response = TestHttpClient.get(port, "/v3/api-docs");

        assertEquals(200, response.statusCode());
        Map<String, Object> sort = queryParameter(response.body(), "/api/v1/complexes", "sort");
        Map<String, Object> size = queryParameter(response.body(), "/api/v1/complexes", "size");
        Map<String, Object> sortSchema = schema(sort);
        Map<String, Object> sizeSchema = schema(size);
        assertAll(
                () -> assertEquals(List.of(
                        "LATEST_ANNOUNCEMENT",
                        "DEPOSIT_ASC",
                        "MONTHLY_RENT_ASC",
                        "AREA_DESC",
                        "COMPLETION_DATE_DESC"
                ), sortSchema.get("enum")),
                () -> assertEquals("LATEST_ANNOUNCEMENT", sortSchema.get("default")),
                () -> assertEquals(20, sizeSchema.get("default")),
                () -> assertTrue(queryParameterNames(response.body(), "/api/v1/complexes")
                        .containsAll(LIST_ONLY_PARAMETERS)),
                () -> assertTrue(queryParameterNames(response.body(), "/api/v1/complexes/map")
                        .stream()
                        .noneMatch(LIST_ONLY_PARAMETERS::contains))
        );
    }

    private void assertRequiredQueryParameter(String document, String path, String parameterName) {
        List<Boolean> requiredValues = JsonPath.read(
                document,
                "$.paths['" + path + "'].get.parameters[?(@.name == '" + parameterName
                        + "' && @.in == 'query')].required"
        );
        assertEquals(List.of(true), requiredValues);
    }

    private List<Map<String, Object>> parameters(String document, String path) {
        return JsonPath.read(document, "$.paths['" + path + "'].get.parameters");
    }

    private Set<String> queryParameterNames(String document, String path) {
        return parameterNames(parameters(document, path));
    }

    private void assertExactQueryParameters(
            List<Map<String, Object>> parameters,
            Set<String> expectedNames
    ) {
        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> {
            assertEquals("query", parameter.get("in"));
            names.add((String) parameter.get("name"));
        });
        assertEquals(expectedNames.size(), parameters.size());
        assertEquals(names.size(), Set.copyOf(names).size());
        assertEquals(expectedNames, Set.copyOf(names));
    }

    private Set<String> parameterNames(List<Map<String, Object>> parameters) {
        return parameters.stream()
                .map(parameter -> (String) parameter.get("name"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Map<String, Object> queryParameter(String document, String path, String parameterName) {
        List<Map<String, Object>> matchingParameters = parameters(document, path).stream()
                .filter(parameter -> parameterName.equals(parameter.get("name")))
                .toList();
        assertEquals(1, matchingParameters.size());
        Map<String, Object> parameter = matchingParameters.getFirst();
        assertEquals("query", parameter.get("in"));
        return parameter;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schema(Map<String, Object> parameter) {
        return (Map<String, Object>) parameter.get("schema");
    }
}
