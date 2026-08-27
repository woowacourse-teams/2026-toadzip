package com.toadzip.backend.global.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
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

    private void assertRequiredQueryParameter(String document, String path, String parameterName) {
        List<Boolean> requiredValues = JsonPath.read(
                document,
                "$.paths['" + path + "'].get.parameters[?(@.name == '" + parameterName
                        + "' && @.in == 'query')].required"
        );
        assertEquals(List.of(true), requiredValues);
    }
}
