package com.toadzip.backend.ingest.location.repository.external;

import com.toadzip.backend.ingest.location.domain.JusoAddress;
import com.toadzip.backend.ingest.location.domain.UtmKCoordinate;
import com.toadzip.backend.ingest.location.exception.AddressLookupException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

/**
 * 행정안전부 도로명주소 개발자센터의 도로명주소 검색 API와 좌표제공 API를 단건으로 부른다.
 * 대량 정제는 위치정보요약DB(ZIP)를 쓰고, 이 클라이언트는 관리자가 주소 하나를 찾을 때만 쓴다.
 */
@Slf4j
@Component
public class JusoAddressClient {

    /** 검색어 자체가 잘못된 오류 코드. 나머지 오류는 키·서비스 문제로 본다. */
    private static final Set<String> KEYWORD_ERRORS = Set.of("E0005", "E0006", "E0008", "E0009", "E0010", "E0011",
            "E0012", "E0013", "E0014", "E0015");

    private final RestClient client;
    private final String searchKey;
    private final String coordinateKey;

    @Autowired
    public JusoAddressClient(
            RestClient.Builder clientBuilder,
            @Value("${app.location.juso-search-api-key:}") String searchKey,
            @Value("${app.location.juso-coordinate-api-key:}") String coordinateKey
    ) {
        this(createClient(clientBuilder), searchKey, coordinateKey);
    }

    JusoAddressClient(RestClient client, String searchKey, String coordinateKey) {
        this.client = client;
        this.searchKey = searchKey.strip();
        this.coordinateKey = coordinateKey.strip();
    }

    public List<JusoAddress> search(String keyword, int count) {
        JsonNode results = call("search", searchKey, builder -> builder.path("/addrlink/addrLinkApi.do")
                .queryParam("confmKey", "{key}").queryParam("currentPage", 1).queryParam("countPerPage", count)
                .queryParam("keyword", "{keyword}").queryParam("resultType", "json").build(searchKey, keyword));
        return StreamSupport.stream(results.path("juso").spliterator(), false).map(this::address).toList();
    }

    /** 건물 출입구 좌표(UTM-K). 아직 건물이 없거나 좌표가 없으면 비어 있다. */
    public Optional<UtmKCoordinate> coordinate(JusoAddress address) {
        JsonNode results = call("coordinate", coordinateKey, builder -> builder.path("/addrlink/addrCoordApi.do")
                .queryParam("confmKey", "{key}").queryParam("admCd", address.legalDongCode())
                .queryParam("rnMgtSn", address.roadNameCode()).queryParam("udrtYn", address.underground() ? 1 : 0)
                .queryParam("buldMnnm", address.buildingMainNumber()).queryParam("buldSlno", address.buildingSubNumber())
                .queryParam("resultType", "json").build(coordinateKey));
        JsonNode first = results.path("juso").path(0);
        String x = first.path("entX").asString(""), y = first.path("entY").asString("");
        if (x.isBlank() || y.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new UtmKCoordinate(new BigDecimal(x), new BigDecimal(y)));
    }

    private JsonNode call(String operation, String key, Function<UriBuilder, URI> uri) {
        long startedAt = System.nanoTime();
        if (key.isBlank()) {
            logFailure(operation, "configuration", "none", startedAt);
            throw new AddressLookupException("주소 검색을 사용할 수 없습니다. 주소 API 키가 설정되지 않았습니다.", true);
        }
        JsonNode root;
        try {
            root = client.get().uri(uri).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            logFailure(operation, "upstream_http", Integer.toString(exception.getStatusCode().value()), startedAt);
            throw new AddressLookupException("주소 검색을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.", true);
        } catch (ResourceAccessException exception) {
            logFailure(operation, exception.getRootCause() instanceof SocketTimeoutException ? "timeout" : "io", "none", startedAt);
            throw new AddressLookupException("주소 검색을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.", true);
        }
        JsonNode results = root == null ? null : root.path("results");
        String code = results == null ? "" : results.path("common").path("errorCode").asString("");
        if ("0".equals(code)) {
            return results;
        }
        String message = results == null ? "" : results.path("common").path("errorMessage").asString("");
        if (KEYWORD_ERRORS.contains(code)) {
            throw new AddressLookupException(message.isBlank() ? "검색어를 확인해 주세요." : message, false);
        }
        logFailure(operation, "upstream_error:" + code, "200", startedAt);
        throw new AddressLookupException("주소 검색을 사용할 수 없습니다. 주소 API 키 승인 상태를 확인해 주세요.", true);
    }

    private JusoAddress address(JsonNode node) {
        return new JusoAddress(node.path("roadAddr").asString(""), node.path("jibunAddr").asString(""),
                node.path("admCd").asString(""), node.path("rnMgtSn").asString(""), "1".equals(node.path("udrtYn").asString("")),
                number(node, "buldMnnm"), number(node, "buldSlno"), "1".equals(node.path("mtYn").asString("")),
                number(node, "lnbrMnnm"), number(node, "lnbrSlno"));
    }

    private int number(JsonNode node, String field) {
        String text = node.path(field).asString("").strip();
        return text.matches("\\d{1,4}") ? Integer.parseInt(text) : 0;
    }

    private void logFailure(String operation, String failure, String upstreamStatus, long startedAt) {
        // 검색어와 키는 개인정보·비밀값일 수 있어 남기지 않는다.
        log.warn("event=external_api.request.failed provider=juso operation={} failure={} upstreamStatus={} durationMs={}",
                operation, failure, upstreamStatus, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private static RestClient createClient(RestClient.Builder clientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return clientBuilder.clone().baseUrl("https://business.juso.go.kr").requestFactory(factory).build();
    }
}
