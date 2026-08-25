package com.toadzip.backend.ingest.repository.external;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class DataGoKrOpenApiClient {

    private static final String SUCCESS = "00";

    private static final String NO_DATA = "03";

    private static final String LH_SUCCESS = "Y";

    private final RestClient restClient;

    private final ObjectMapper objectMapper;

    private final String baseUrl;

    private final String serviceKey;

    private final String apiName;

    public DataGoKrOpenApiClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String baseUrl,
            String serviceKey,
            String apiName
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.serviceKey = encodeServiceKey(serviceKey);
        this.apiName = apiName;
    }

    public ExternalApiResponse get(String path, MultiValueMap<String, String> params) {
        requireConfigured();
        URI requestUri = buildUri(path, params);
        String apiData = requestApiData(requestUri);
        JsonNode responseBody = parseApiData(apiData);
        verifyResultCode(responseBody);
        return new ExternalApiResponse(apiData, responseBody);
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

    public static List<JsonNode> findRows(JsonNode root, String locator) {
        JsonNode found = findByKey(root, locator);
        if (locator.startsWith("/")) {
            found = root.at(locator);
        }
        if (found.isArray()) {
            List<JsonNode> rows = new ArrayList<>(found.size());
            found.forEach(rows::add);
            return rows;
        }
        if (found.isObject()) {
            return List.of(found);
        }
        return List.of();
    }

    private String requestApiData(URI requestUri) {
        try {
            String apiData = restClient.get().uri(requestUri).retrieve().body(String.class);
            if (apiData == null || apiData.isBlank()) {
                throw new ExternalApiRequestException(apiName + " 응답이 비어 있습니다.");
            }
            return apiData;
        }
        catch (ExternalApiRequestException exception) {
            throw exception;
        }
        catch (HttpServerErrorException exception) {
            throw ExternalApiRequestException.retryable(
                    httpFailureMessage(exception.getStatusCode().value()),
                    exception
            );
        }
        catch (ResourceAccessException exception) {
            throw ExternalApiRequestException.retryable(apiName + " 외부 API 연결에 실패했습니다.", exception);
        }
        catch (HttpClientErrorException exception) {
            throw new ExternalApiRequestException(
                    httpFailureMessage(exception.getStatusCode().value()),
                    exception
            );
        }
        catch (RuntimeException exception) {
            throw new ExternalApiRequestException(apiName + " 외부 API 호출에 실패했습니다.", exception);
        }
    }

    private JsonNode parseApiData(String apiData) {
        try {
            return objectMapper.readTree(apiData);
        }
        catch (RuntimeException exception) {
            throw new ExternalApiRequestException(apiName + " 응답 형식이 올바르지 않습니다.", exception);
        }
    }

    private void requireConfigured() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ExternalApiRequestException(apiName + " API 주소가 비어 있습니다.");
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new ExternalApiRequestException("공공데이터 서비스키가 비어 있습니다.");
        }
    }

    private String httpFailureMessage(int statusCode) {
        return apiName + " 외부 API 호출에 실패했습니다: HTTP " + statusCode;
    }

    private void verifyResultCode(JsonNode root) {
        JsonNode header = root.path("response").path("header");
        if (header.isObject()) {
            verifyMolitHeader(header);
            return;
        }
        verifyLhHeader(root);
    }

    private void verifyMolitHeader(JsonNode header) {
        String code = header.path("resultCode").asString("");
        if (SUCCESS.equals(code) || NO_DATA.equals(code)) {
            return;
        }
        String message = header.path("resultMsg").asString("");
        throw new ExternalApiRequestException("외부 API 오류 resultCode=" + code + ", " + message);
    }

    private void verifyLhHeader(JsonNode root) {
        List<JsonNode> headers = findRows(root, "resHeader");
        if (headers.isEmpty()) {
            throw new ExternalApiRequestException("외부 API 응답에 resHeader가 없습니다.");
        }
        JsonNode header = headers.getFirst();
        String code = header.path("SS_CODE").asString("");
        if (LH_SUCCESS.equals(code)) {
            return;
        }
        String message = header.path("RS_MSG").asString("");
        throw new ExternalApiRequestException("외부 API 오류 SS_CODE=" + code + ", " + message);
    }

    private static JsonNode findByKey(JsonNode root, String key) {
        if (!root.isArray()) {
            return root.path(key);
        }
        for (JsonNode element : root) {
            JsonNode found = element.path(key);
            if (!found.isMissingNode()) {
                return found;
            }
        }
        return root.path(key);
    }

    private static String encodeServiceKey(String raw) {
        if (raw == null || raw.contains("%")) {
            return raw;
        }
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }
}
