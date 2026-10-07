package com.toadzip.backend.search.repository;

import com.toadzip.backend.housing.domain.MapCoordinate;
import com.toadzip.backend.search.domain.LocationSearchType;
import com.toadzip.backend.search.exception.LocationSearchUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Repository
public class KakaoLocationSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(KakaoLocationSearchRepository.class);
    private final RestClient client;
    private final String apiKey;

    @Autowired
    public KakaoLocationSearchRepository(@Value("${app.search.kakao-rest-api-key:}") String apiKey) {
        this(createClient(), apiKey);
    }

    KakaoLocationSearchRepository(RestClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey.strip();
    }

    public LocationSearchPage search(String query, LocationSearchType type, int page, int size) {
        if (apiKey.isBlank()) {
            throw new LocationSearchUnavailableException();
        }
        if (page >= 45 || (type == LocationSearchType.PLACE && page * size >= 45)) {
            return new LocationSearchPage(List.of(), false, null);
        }
        try {
            return readPage(request(query, type, page, size), type, page, size);
        } catch (RuntimeException exception) {
            // Upstream exceptions can contain the query or response body. Log only the failure class.
            log.warn("위치 검색 제공자 요청 실패: {}", exception.getClass().getSimpleName());
            throw new LocationSearchUnavailableException();
        }
    }

    private JsonNode request(String query, LocationSearchType type, int page, int size) {
        String path = "/v2/local/search/keyword.json";
        if (type == LocationSearchType.REGION) {
            path = "/v2/local/search/address.json";
        }
        String searchPath = path;
        return client.get()
                .uri(builder -> builder.path(searchPath).queryParam("query", "{query}")
                        .queryParam("page", page + 1).queryParam("size", size).build(query))
                .header("Authorization", "KakaoAK " + apiKey)
                .retrieve().body(JsonNode.class);
    }

    private LocationSearchPage readPage(JsonNode root, LocationSearchType type, int page, int size) {
        JsonNode documents = root.required("documents");
        JsonNode meta = root.required("meta");
        if (!documents.isArray() || !meta.required("is_end").isBoolean()
                || !meta.required("pageable_count").isIntegralNumber()) {
            throw new IllegalArgumentException("Invalid location search response");
        }
        List<LocationSearchItem> items = StreamSupport.stream(documents.spliterator(), false)
                .filter(document -> type == LocationSearchType.PLACE || isRegion(document))
                .map(document -> item(document, type))
                .distinct()
                .toList();
        boolean hasNext = !meta.required("is_end").asBoolean() && page < 44;
        Long totalCount = null;
        if (type == LocationSearchType.PLACE) {
            totalCount = Math.min(45L, meta.required("pageable_count").asLong());
            hasNext = hasNext && (page + 1) * size < totalCount;
        }
        return new LocationSearchPage(items, hasNext, totalCount);
    }

    private boolean isRegion(JsonNode document) {
        return "REGION".equals(document.required("address_type").asString());
    }

    private LocationSearchItem item(JsonNode document, LocationSearchType type) {
        MapCoordinate coordinate = new MapCoordinate(
                new BigDecimal(text(document, "y")), new BigDecimal(text(document, "x"))
        );
        if (type == LocationSearchType.REGION) {
            String name = text(document, "address_name");
            return new LocationSearchItem(type, "local-region:" + name, name, "행정구역", coordinate);
        }
        String address = text(document, "address_name");
        String roadAddress = document.path("road_address_name").asString("");
        if (!roadAddress.isBlank()) {
            address = roadAddress;
        }
        return new LocationSearchItem(type, text(document, "id"), text(document, "place_name"),
                text(document, "category_name") + " · " + address, coordinate);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Invalid location search field: " + field);
        }
        return value.asString();
    }

    private static RestClient createClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl("https://dapi.kakao.com").requestFactory(factory).build();
    }
}
