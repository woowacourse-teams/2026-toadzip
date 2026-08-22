package com.toadzip.backend.ingest.repository.external;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.toadzip.backend.ingest.dto.ExternalDataResponse;
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

    private final String sourceName;

    public DataGoKrOpenApiClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String baseUrl,
            String serviceKey,
            String sourceName
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.serviceKey = encodeServiceKey(serviceKey);
        this.sourceName = sourceName;
    }

    public ExternalDataResponse get(String path, MultiValueMap<String, String> params) {
        requireConfigured();
        URI requestUri = buildUri(path, params);
        String rawPayload = requestRawPayload(requestUri);
        JsonNode body = parsePayload(rawPayload);
        verifyResultCode(body);
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
        throw new ExternalDataRequestException("원천 오류 resultCode=" + code + ", " + message);
    }

    private void verifyLhHeader(JsonNode root) {
        List<JsonNode> headers = findRows(root, "resHeader");
        if (headers.isEmpty()) {
            throw new ExternalDataRequestException("원천 응답에 resHeader가 없습니다.");
        }
        JsonNode header = headers.getFirst();
        String code = header.path("SS_CODE").asString("");
        if (LH_SUCCESS.equals(code)) {
            return;
        }
        String message = header.path("RS_MSG").asString("");
        throw new ExternalDataRequestException("원천 오류 SS_CODE=" + code + ", " + message);
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
