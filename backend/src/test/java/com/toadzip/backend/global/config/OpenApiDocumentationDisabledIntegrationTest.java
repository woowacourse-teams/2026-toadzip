package com.toadzip.backend.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.web-application-type=servlet"
)
@ActiveProfiles("test")
class OpenApiDocumentationDisabledIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void 기본_설정은_OpenAPI_문서를_노출하지_않는다() throws Exception {
        HttpResponse<String> response = TestHttpClient.get(port, "/v3/api-docs");

        assertEquals(404, response.statusCode());
    }

    @Test
    void 기본_설정은_Swagger_UI를_노출하지_않는다() throws Exception {
        HttpResponse<String> response = TestHttpClient.get(port, "/swagger-ui/index.html");

        assertEquals(404, response.statusCode());
    }
}
