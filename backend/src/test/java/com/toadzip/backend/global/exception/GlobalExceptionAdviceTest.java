package com.toadzip.backend.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(GlobalExceptionAdviceTest.TestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionAdviceTest.TestController.class)
class GlobalExceptionAdviceTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void DTO의_모든_필드_검증_실패를_오류_목록으로_반환한다() throws Exception {
        mockMvc.perform(post("/test/errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "",
                                  "sourceUrl": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'title')].reason").value("필수 값입니다."))
                .andExpect(jsonPath("$.errors[?(@.field == 'sourceUrl')].reason").value("필수 값입니다."));
    }

    @Test
    void ModelAttribute_변환_실패는_내부_타입_정보_없이_안전한_형식_오류로_반환한다() throws Exception {
        mockMvc.perform(get("/test/errors/binding")
                        .param("amount", "not-a-long"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(".*\\S.*")))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].reason").value("형식이 올바르지 않습니다."))
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("org.springframework"))))
                .andExpect(content().string(not(containsString("com.toadzip"))))
                .andExpect(content().string(not(containsString("Failed to convert"))))
                .andExpect(content().string(not(containsString("For input string"))));
    }

    @Test
    void 읽을_수_없는_JSON은_내부_파싱_정보를_노출하지_않는다() throws Exception {
        mockMvc.perform(post("/test/errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void 지원하지_않는_HTTP_메서드는_405_오류_계약으로_반환한다() throws Exception {
        mockMvc.perform(put("/test/errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 HTTP 메서드입니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 지원하지_않는_미디어_타입은_415_오류_계약으로_반환한다() throws Exception {
        mockMvc.perform(post("/test/errors")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 미디어 타입입니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 필수_요청_파라미터_누락을_필드_검증_오류로_반환한다() throws Exception {
        mockMvc.perform(get("/test/errors/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.errors[0].field").value("requiredCode"))
                .andExpect(jsonPath("$.errors[0].reason").value("필수 값입니다."));
    }

    @Test
    void 존재하지_않는_경로는_404_오류_계약으로_반환한다() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 예상하지_못한_오류는_내부_예외_메시지를_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("database password"))));
    }

    @Test
    void 인증_제공자_장애는_안전한_500_오류_계약으로_반환한다() throws Exception {
        mockMvc.perform(get("/test/errors/authentication-service"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("database unavailable"))));
    }

    @RestController
    @RequestMapping("/test/errors")
    static class TestController {

        @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
        void validate(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/required")
        void requireParameter(@RequestParam String requiredCode) {
        }

        @GetMapping("/binding")
        void bind(@Valid @ModelAttribute BindingRequest request) {
        }

        @GetMapping("/unexpected")
        void failUnexpectedly() {
            throw new IllegalStateException("database password must not be exposed");
        }

        @GetMapping("/authentication-service")
        void failAuthenticationService() {
            throw new AuthenticationServiceException("database unavailable");
        }
    }

    record TestRequest(
            @NotBlank(message = "필수 값입니다.") String title,
            @NotBlank(message = "필수 값입니다.") String sourceUrl
    ) {
    }

    record BindingRequest(Long amount) {
    }
}
