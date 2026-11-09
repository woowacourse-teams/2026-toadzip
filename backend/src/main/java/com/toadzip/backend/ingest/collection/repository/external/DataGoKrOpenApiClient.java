package com.toadzip.backend.ingest.collection.repository.external;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;

public class DataGoKrOpenApiClient {

    private static final String DAILY_RATE_LIMIT_CODE = "22";

    /** 공공데이터포털 게이트웨이가 키를 받지 않을 때의 사유 코드(접근 거부·미등록 키·기간 만료·미등록 IP). */
    private static final Set<String> SERVICE_KEY_REJECTED_CODES = Set.of("20", "30", "31", "32");

    private static final Pattern XML_REASON_CODE =
            Pattern.compile("<returnReasonCode>\\s*(\\d+)\\s*</returnReasonCode>");

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
        this.serviceKey = serviceKey;
        this.sourceName = sourceName;
        this.responseStatusValidator = responseStatusValidator;
    }

    public JsonNode get(String path, MultiValueMap<String, String> params) {
        try {
            requireConfigured();
            URI requestUri = buildUri(path, params);
            String rawPayload = requestRawPayload(requestUri);
            JsonNode body = parsePayload(rawPayload);
            validateGatewayHeader(body);
            responseStatusValidator.validate(body);
            return body;
        }
        catch (ExternalDataRequestException exception) {
            throw safeFailure(exception);
        }
        catch (RuntimeException exception) {
            throw new ExternalDataRequestException(sourceName + " 외부 API 호출에 실패했습니다.");
        }
    }

    URI buildUri(String path, MultiValueMap<String, String> params) {
        String query = UriComponentsBuilder.newInstance()
                .queryParams(params)
                .build()
                .encode(StandardCharsets.UTF_8)
                .getQuery();
        String uri = "%s/%s?serviceKey=%s".formatted(baseUrl, path, encodeServiceKey(serviceKey));
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
            int statusCode = exception.getStatusCode().value();
            if (statusCode == 429) {
                throw tooManyRequests(exception);
            }
            if (statusCode == 401 || statusCode == 403) {
                throw new ExternalDataRequestException(serviceKeyRejectedMessage("HTTP " + statusCode));
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
            return objectMapper.reader()
                    .with(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                    .readTree(rawPayload);
        }
        catch (RuntimeException exception) {
            Matcher reason = XML_REASON_CODE.matcher(rawPayload);
            if (reason.find() && SERVICE_KEY_REJECTED_CODES.contains(reason.group(1))) {
                throw new ExternalDataRequestException(serviceKeyRejectedMessage("resultCode=" + reason.group(1)));
            }
            throw new ExternalDataRequestException(sourceName + " 응답 형식이 올바르지 않습니다.", exception);
        }
    }

    private void requireConfigured() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ExternalDataRequestException(sourceName + " API 주소가 비어 있습니다.");
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new ExternalDataRequestException(
                    "서버 수집 키가 설정되지 않았습니다. 서버 환경변수 DATA_GO_KR_SERVICE_KEY를 확인해 주세요.");
        }
    }

    private void validateGatewayHeader(JsonNode body) {
        JsonNode header = body.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        String code = header.path("returnReasonCode").asString("");
        if (SERVICE_KEY_REJECTED_CODES.contains(code)) {
            throw new ExternalDataRequestException(serviceKeyRejectedMessage("resultCode=" + code));
        }
        if (DAILY_RATE_LIMIT_CODE.equals(code) || "23".equals(code)) {
            throw ExternalDataRequestException.rateLimited(
                    sourceName + " 외부 API 호출 제한: resultCode=" + code,
                    null,
                    !DAILY_RATE_LIMIT_CODE.equals(code)
            );
        }
    }

    private String serviceKeyRejectedMessage(String reason) {
        return sourceName + " 외부 API가 서버 수집 키를 거부했습니다(" + reason + "). "
                + "서버 환경변수 DATA_GO_KR_SERVICE_KEY 값과 공공데이터포털 활용 신청 상태를 확인해 주세요.";
    }

    private String httpFailureMessage(int statusCode) {
        return sourceName + " 외부 API 호출에 실패했습니다: HTTP " + statusCode;
    }

    private ExternalDataRequestException safeFailure(ExternalDataRequestException failure) {
        String message = failure.getMessage();
        if (serviceKey != null && !serviceKey.isBlank()) {
            message = message.replace(serviceKey, "[REDACTED]").replace(encodeServiceKey(serviceKey), "[REDACTED]");
            message = message.replace(decodedServiceKey(serviceKey), "[REDACTED]");
        }
        if (failure.isRateLimited()) {
            return ExternalDataRequestException.rateLimited(message, null, failure.isRetryable());
        }
        if (failure.isRetryable()) {
            return ExternalDataRequestException.retryable(message);
        }
        return new ExternalDataRequestException(message);
    }

    private String decodedServiceKey(String key) {
        try {
            return URLDecoder.decode(encodeServiceKey(key), StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException exception) {
            return key;
        }
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
        if (raw == null) {
            return raw;
        }
        if (raw.contains("%")) {
            return URLEncoder.encode(URLDecoder.decode(raw, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }
}
