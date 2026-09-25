package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class DataGoKrOpenApiClient {

    private static final String DAILY_RATE_LIMIT_CODE = "22";

    private final RestClient restClient;

    private final ObjectMapper objectMapper;

    private final String baseUrl;

    private final String serviceKey;

    private final String sourceName;

    private final ExternalDataResponseStatusValidator responseStatusValidator;

    public DataGoKrOpenApiClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String baseUrl,
            String serviceKey,
            String sourceName,
            ExternalDataResponseStatusValidator responseStatusValidator
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.serviceKey = encodeServiceKey(serviceKey);
        this.sourceName = sourceName;
        this.responseStatusValidator = responseStatusValidator;
    }

    public ExternalDataResponse get(String path, MultiValueMap<String, String> params) {
        requireConfigured();
        URI requestUri = buildUri(path, params);
        String rawPayload = requestRawPayload(requestUri);
        JsonNode body = parsePayload(rawPayload);
        validateGatewayRateLimit(body);
        responseStatusValidator.validate(body);
        return new ExternalDataResponse(rawPayload, body);
    }

    URI buildUri(String path, MultiValueMap<String, String> params) {
        String query = UriComponentsBuilder.newInstance()
                .queryParams(params)
                .build()
                .encode(StandardCharsets.UTF_8)
                .getQuery();
        String uri = "%s/%s?serviceKey=%s".formatted(baseUrl, path, serviceKey);
        if (query == null || query.isBlank()) {
            return URI.create(uri);
        }
        return URI.create(uri + "&" + query);
    }

    private String requestRawPayload(URI requestUri) {
        try {
            String rawPayload = restClient.get().uri(requestUri).retrieve().body(String.class);
            if (rawPayload == null || rawPayload.isBlank()) {
                throw new ExternalDataRequestException(sourceName + " 응답이 비어 있습니다.");
            }
            return rawPayload;
        }
        catch (ExternalDataRequestException exception) {
            throw exception;
        }
        catch (HttpServerErrorException exception) {
            throw ExternalDataRequestException.retryable(
                    httpFailureMessage(exception.getStatusCode().value()),
                    exception
            );
        }
        catch (ResourceAccessException exception) {
            throw ExternalDataRequestException.retryable(sourceName + " 외부 API 연결에 실패했습니다.", exception);
        }
        catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().value() == 429) {
                throw tooManyRequests(exception);
            }
            throw new ExternalDataRequestException(
                    httpFailureMessage(exception.getStatusCode().value()),
                    exception
            );
        }
        catch (RuntimeException exception) {
            throw new ExternalDataRequestException(sourceName + " 외부 API 호출에 실패했습니다.", exception);
        }
    }

    private JsonNode parsePayload(String rawPayload) {
        try {
            return objectMapper.readTree(rawPayload);
        }
        catch (RuntimeException exception) {
            throw new ExternalDataRequestException(sourceName + " 응답 형식이 올바르지 않습니다.", exception);
        }
    }

    private void requireConfigured() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ExternalDataRequestException(sourceName + " API 주소가 비어 있습니다.");
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new ExternalDataRequestException("공공데이터 서비스키가 비어 있습니다.");
        }
    }

    private void validateGatewayRateLimit(JsonNode body) {
        JsonNode header = body.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        String code = header.path("returnReasonCode").asString("");
        if (DAILY_RATE_LIMIT_CODE.equals(code) || "23".equals(code)) {
            throw ExternalDataRequestException.rateLimited(
                    sourceName + " 외부 API 호출 제한: resultCode=" + code,
                    null,
                    !DAILY_RATE_LIMIT_CODE.equals(code)
            );
        }
    }

    private String httpFailureMessage(int statusCode) {
        return sourceName + " 외부 API 호출에 실패했습니다: HTTP " + statusCode;
    }

    private ExternalDataRequestException tooManyRequests(HttpClientErrorException exception) {
        JsonNode header = gatewayErrorHeader(exception.getResponseBodyAsString());
        String code = header.path("returnReasonCode").asString("");
        String message = header.path("returnAuthMsg").asString("");
        String reason = httpFailureMessage(exception.getStatusCode().value());
        if (!code.isBlank()) {
            reason += ", resultCode=" + code + ", " + message;
        }
        if (DAILY_RATE_LIMIT_CODE.equals(code)) {
            return ExternalDataRequestException.rateLimited(reason, exception, false);
        }
        return ExternalDataRequestException.rateLimited(reason, exception, true);
    }

    private JsonNode gatewayErrorHeader(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(responseBody)
                    .path("OpenAPI_ServiceResponse")
                    .path("cmmMsgHeader");
        }
        catch (RuntimeException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private static String encodeServiceKey(String raw) {
        if (raw == null || raw.contains("%")) {
            return raw;
        }
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }
}
