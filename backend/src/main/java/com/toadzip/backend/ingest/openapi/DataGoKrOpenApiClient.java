package com.toadzip.backend.ingest.openapi;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class DataGoKrOpenApiClient {

	private static final String SUCCESS = "00";

	private static final String NO_DATA = "03";

	private final RestClient restClient;

	private final ObjectMapper objectMapper;

	private final String baseUrl;

	private final String serviceKey;

	private final String sourceName;

	public DataGoKrOpenApiClient(RestClient restClient, ObjectMapper objectMapper, String baseUrl, String serviceKey,
			String sourceName) {
		this.restClient = restClient;
		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
		this.serviceKey = encodeServiceKey(serviceKey);
		this.sourceName = sourceName;
	}

	public <T> List<T> getList(String path, MultiValueMap<String, String> params, String listPointer, Class<T> type) {
		JsonNode root = get(path, params);
		JsonNode found = root.at(listPointer);
		List<JsonNode> rows = rowsOf(found);
		List<T> items = new ArrayList<>(rows.size());
		for (JsonNode row : rows) {
			items.add(objectMapper.convertValue(row, type));
		}
		return items;
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

	private JsonNode get(String path, MultiValueMap<String, String> params) {
		requireConfigured();
		JsonNode root = restClient.get().uri(buildUri(path, params)).retrieve().body(JsonNode.class);
		if (root == null) {
			throw new IllegalStateException("%s 응답이 비어 있습니다: %s".formatted(sourceName, path));
		}
		verifyResultCode(root);
		return root;
	}

	private void requireConfigured() {
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new IllegalStateException("%s API 주소가 비어 있습니다.".formatted(sourceName));
		}
		if (serviceKey == null || serviceKey.isBlank()) {
			throw new IllegalStateException("공공데이터 서비스키가 비어 있습니다.");
		}
	}

	private void verifyResultCode(JsonNode root) {
		JsonNode header = root.path("response").path("header");
		String code = header.path("resultCode").asString("");
		if (SUCCESS.equals(code) || NO_DATA.equals(code)) {
			return;
		}
		String message = header.path("resultMsg").asString("");
		throw new IllegalStateException("원천 오류 resultCode=%s, %s".formatted(code, message));
	}

	private List<JsonNode> rowsOf(JsonNode found) {
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

	private static String encodeServiceKey(String raw) {
		if (raw == null || raw.contains("%")) {
			return raw;
		}
		return URLEncoder.encode(raw, StandardCharsets.UTF_8);
	}

}
