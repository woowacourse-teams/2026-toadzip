package com.toadzip.backend.search.repository;

import com.toadzip.backend.housing.domain.MapCoordinate;
import com.toadzip.backend.search.domain.LocationSearchType;
import com.toadzip.backend.search.exception.LocationSearchUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

@Repository
public class NaverLocationSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(NaverLocationSearchRepository.class);
    private static final Set<String> REGION_ELEMENTS = Set.of("SIDO", "SIGUGUN", "DONGMYUN", "RI");
    private static final Set<String> DETAIL_ELEMENTS = Set.of(
            "ROAD_NAME", "BUILDING_NUMBER", "BUILDING_NAME", "LAND_NUMBER"
    );
    private final RestClient searchClient;
    private final RestClient mapsClient;
    private final String searchClientId;
    private final String searchClientSecret;
    private final String mapsApiKeyId;
    private final String mapsApiKey;

    @Autowired
    public NaverLocationSearchRepository(
            @Value("${app.search.naver-client-id:}") String searchClientId,
            @Value("${app.search.naver-client-secret:}") String searchClientSecret,
            @Value("${app.search.naver-maps-api-key-id:}") String mapsApiKeyId,
            @Value("${app.search.naver-maps-api-key:}") String mapsApiKey
    ) {
        this(createClient("https://openapi.naver.com"), createClient("https://maps.apigw.ntruss.com"),
                searchClientId, searchClientSecret, mapsApiKeyId, mapsApiKey);
    }

    NaverLocationSearchRepository(RestClient searchClient, RestClient mapsClient,
            String searchClientId, String searchClientSecret, String mapsApiKeyId, String mapsApiKey) {
        this.searchClient = searchClient;
        this.mapsClient = mapsClient;
        this.searchClientId = searchClientId.strip();
        this.searchClientSecret = searchClientSecret.strip();
        this.mapsApiKeyId = mapsApiKeyId.strip();
        this.mapsApiKey = mapsApiKey.strip();
    }

    public LocationSearchPage search(String query, LocationSearchType type, int page, int size) {
        try {
            if (type == LocationSearchType.REGION) {
                return searchRegions(query, page, size);
            }
            return searchPlaces(query, page, size);
        } catch (RuntimeException exception) {
            // Upstream exceptions can contain the query or response body. Log only the failure class.
            log.warn("네이버 위치 검색 요청 실패: {}", exception.getClass().getSimpleName());
            throw new LocationSearchUnavailableException();
        }
    }

    private LocationSearchPage searchPlaces(String query, int page, int size) {
        requireCredentials(searchClientId, searchClientSecret);
        // Naver Local Search supports start=1 only; subsequent requests would repeat the first page.
        if (page > 0) {
            return new LocationSearchPage(List.of(), false, null);
        }
        JsonNode root = searchClient.get()
                .uri(builder -> builder.path("/v1/search/local.json").queryParam("query", "{query}")
                        .queryParam("display", Math.min(size, 5)).queryParam("start", 1).build(query))
                .header("X-Naver-Client-Id", searchClientId)
                .header("X-Naver-Client-Secret", searchClientSecret)
                .accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
        JsonNode items = array(root, "items");
        long total = nonnegativeCount(root, "total");
        List<LocationSearchItem> results = StreamSupport.stream(items.spliterator(), false)
                .map(this::place).distinct().limit(Math.min(size, 5)).toList();
        return new LocationSearchPage(results, false, Math.min(total, 5));
    }

    private LocationSearchPage searchRegions(String query, int page, int size) {
        requireCredentials(mapsApiKeyId, mapsApiKey);
        JsonNode root = mapsClient.get()
                .uri(builder -> builder.path("/map-geocode/v2/geocode").queryParam("query", "{query}")
                        .queryParam("page", page + 1).queryParam("count", size).build(query))
                .header("x-ncp-apigw-api-key-id", mapsApiKeyId).header("x-ncp-apigw-api-key", mapsApiKey)
                .accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
        if (!"OK".equals(text(root, "status"))) {
            throw new IllegalArgumentException("Unsuccessful geocoding response");
        }
        JsonNode addresses = array(root, "addresses");
        long total = nonnegativeCount(root.required("meta"), "totalCount");
        List<LocationSearchItem> results = StreamSupport.stream(addresses.spliterator(), false)
                .filter(this::isRegion).map(this::region).distinct().toList();
        boolean hasNext = !addresses.isEmpty() && (page + 1L) * size < total && page < 100;
        return new LocationSearchPage(results, hasNext, null);
    }

    private LocationSearchItem place(JsonNode node) {
        String title = HtmlUtils.htmlUnescape(text(node, "title").replaceAll("<[^>]*>", ""));
        if (title.isBlank()) {
            throw new IllegalArgumentException("Empty place title");
        }
        String address = text(node, "address");
        String roadAddress = node.path("roadAddress").asString("");
        if (!roadAddress.isBlank()) {
            address = roadAddress;
        }
        MapCoordinate coordinate = new MapCoordinate(scaledCoordinate(node, "mapy"), scaledCoordinate(node, "mapx"));
        String id = "naver-place:" + title + ":" + address + ":" + coordinate.latitude() + ":" + coordinate.longitude();
        return new LocationSearchItem(LocationSearchType.PLACE, id, title,
                HtmlUtils.htmlUnescape(text(node, "category")) + " · " + address, coordinate);
    }

    private boolean isRegion(JsonNode node) {
        JsonNode elements = array(node, "addressElements");
        if (StreamSupport.stream(elements.spliterator(), false)
                .anyMatch(element -> isNamedElement(element, DETAIL_ELEMENTS))) {
            return false;
        }
        return StreamSupport.stream(elements.spliterator(), false)
                .anyMatch(element -> isNamedElement(element, REGION_ELEMENTS));
    }

    private boolean isNamedElement(JsonNode element, Set<String> allowedTypes) {
        String name = element.required("longName").asString();
        return !name.isBlank() && StreamSupport.stream(array(element, "types").spliterator(), false)
                .anyMatch(type -> allowedTypes.contains(type.asString()));
    }

    private LocationSearchItem region(JsonNode node) {
        String title = StreamSupport.stream(array(node, "addressElements").spliterator(), false)
                .filter(element -> isNamedElement(element, REGION_ELEMENTS))
                .map(element -> element.required("longName").asString())
                .collect(Collectors.joining(" "));
        MapCoordinate coordinate = new MapCoordinate(new BigDecimal(text(node, "y")), new BigDecimal(text(node, "x")));
        return new LocationSearchItem(LocationSearchType.REGION, "naver-region:" + title, title, "행정구역", coordinate);
    }

    private BigDecimal scaledCoordinate(JsonNode node, String field) {
        String number = coordinateNumber(node, field);
        if (!number.matches("-?\\d+")) {
            throw new IllegalArgumentException("Invalid WGS84 coordinate");
        }
        return new BigDecimal(number).movePointLeft(7);
    }

    private String coordinateNumber(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (value.isIntegralNumber()) {
            return value.toString();
        }
        return text(node, field);
    }

    private JsonNode array(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isArray()) {
            throw new IllegalArgumentException("Invalid location search array");
        }
        return value;
    }

    private long nonnegativeCount(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
            throw new IllegalArgumentException("Invalid location search count");
        }
        return value.asLong();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Invalid location search field: " + field);
        }
        return value.asString();
    }

    private void requireCredentials(String id, String secret) {
        if (id.isBlank() || secret.isBlank()) {
            throw new LocationSearchUnavailableException();
        }
    }

    private static RestClient createClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
