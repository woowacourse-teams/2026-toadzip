package com.toadzip.backend.ingest.repository.external;

import static com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason.EXTERNAL_API_ERROR;
import static com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason.NOT_CONFIGURED;
import static com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason.RATE_LIMIT_EXCEEDED;

import com.toadzip.backend.ingest.configuration.JusoGeocodingProperties;
import com.toadzip.backend.ingest.domain.JusoAddressCode;
import com.toadzip.backend.ingest.domain.RoadAddressCandidate;
import com.toadzip.backend.ingest.domain.UtmKCoordinate;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.repository.RoadAddressCoordinateRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

public class JusoRoadAddressCoordinateRepository implements RoadAddressCoordinateRepository {

    private static final String SUCCESS_CODE = "0";

    private static final String TOO_MANY_REQUESTS_CODE = "E0007";

    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4)
    );

    private final RestClient restClient;

    private final JusoGeocodingProperties properties;

    private final Sleeper sleeper;

    public JusoRoadAddressCoordinateRepository(
            RestClient restClient,
            JusoGeocodingProperties properties
    ) {
        this(restClient, properties, duration -> Thread.sleep(duration.toMillis()));
    }

    JusoRoadAddressCoordinateRepository(
            RestClient restClient,
            JusoGeocodingProperties properties,
            Sleeper sleeper
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    @Override
    public List<RoadAddressCandidate> search(String roadAddress) {
        requireConfigured(properties.addressKey(), "도로명주소 검색 API");
        MultiValueMap<String, String> form = commonForm(properties.addressKey());
        form.add("currentPage", "1");
        form.add("countPerPage", "100");
        form.add("keyword", roadAddress);
        JsonNode body = request("addrLinkApi.do", form);
        return candidatesOf(body);
    }

    @Override
    public Optional<UtmKCoordinate> findCoordinate(JusoAddressCode addressCode) {
        requireConfigured(properties.coordinateKey(), "좌표제공 검색 API");
        MultiValueMap<String, String> form = commonForm(properties.coordinateKey());
        form.add("admCd", addressCode.administrativeCode());
        form.add("rnMgtSn", addressCode.roadNameCode());
        form.add("udrtYn", addressCode.underground());
        form.add("buldMnnm", addressCode.buildingMainNumber());
        form.add("buldSlno", addressCode.buildingSubNumber());
        JsonNode body = request("addrCoordApi.do", form);
        return coordinateOf(body);
    }

    private JsonNode request(String path, MultiValueMap<String, String> form) {
        for (int attempt = 0; ; attempt++) {
            try {
                return requestOnce(path, form);
            }
            catch (JusoApiException exception) {
                if (!exception.isRetryable() || attempt >= RETRY_DELAYS.size()) {
                    throw exception;
                }
                sleep(RETRY_DELAYS.get(attempt));
            }
        }
    }

    private JsonNode requestOnce(String path, MultiValueMap<String, String> form) {
        try {
            JsonNode body = restClient.post()
                    .uri(endpoint(path))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw externalFailure("주소 API 응답이 비어 있습니다.");
            }
            verifySuccess(body);
            return body;
        }
        catch (JusoApiException exception) {
            throw exception;
        }
        catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().value() == 429) {
                throw new JusoApiException(
                        RATE_LIMIT_EXCEEDED,
                        "주소 API 호출 제한에 도달했습니다.",
                        exception,
                        true
                );
            }
            throw new JusoApiException(
                    EXTERNAL_API_ERROR,
                    "주소 API 호출에 실패했습니다.",
                    exception,
                    false
            );
        }
        catch (HttpServerErrorException | ResourceAccessException exception) {
            throw new JusoApiException(
                    EXTERNAL_API_ERROR,
                    "주소 API 호출에 실패했습니다.",
                    exception,
                    true
            );
        }
        catch (RuntimeException exception) {
            throw new JusoApiException(
                    EXTERNAL_API_ERROR,
                    "주소 API 응답 처리에 실패했습니다.",
                    exception
            );
        }
    }

    private URI endpoint(String path) {
        String baseUrl = properties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new JusoApiException(NOT_CONFIGURED, "주소 API 주소가 설정되지 않았습니다.");
        }
        return URI.create(baseUrl + "/" + path);
    }

    private MultiValueMap<String, String> commonForm(String key) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("confmKey", key);
        form.add("resultType", "json");
        return form;
    }

    private List<RoadAddressCandidate> candidatesOf(JsonNode body) {
        JsonNode rows = body.path("results").path("juso");
        if (!rows.isArray()) {
            return List.of();
        }
        List<RoadAddressCandidate> candidates = new ArrayList<>(rows.size());
        rows.forEach(row -> candidates.add(candidateOf(row)));
        return List.copyOf(candidates);
    }

    private RoadAddressCandidate candidateOf(JsonNode row) {
        return new RoadAddressCandidate(
                requiredText(row, "roadAddr"),
                requiredText(row, "roadAddrPart1"),
                new JusoAddressCode(
                        requiredText(row, "admCd"),
                        requiredText(row, "rnMgtSn"),
                        requiredText(row, "udrtYn"),
                        requiredText(row, "buldMnnm"),
                        row.path("buldSlno").asString("0")
                )
        );
    }

    private Optional<UtmKCoordinate> coordinateOf(JsonNode body) {
        JsonNode rows = body.path("results").path("juso");
        if (!rows.isArray() || rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() != 1) {
            throw externalFailure("좌표 API 응답이 한 건으로 확정되지 않았습니다.");
        }
        JsonNode row = rows.get(0);
        String x = row.path("entX").asString("");
        String y = row.path("entY").asString("");
        if (x.isBlank() || y.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new UtmKCoordinate(new BigDecimal(x), new BigDecimal(y)));
        }
        catch (NumberFormatException exception) {
            throw new JusoApiException(
                    EXTERNAL_API_ERROR,
                    "좌표 API 좌표 형식이 올바르지 않습니다.",
                    exception
            );
        }
    }

    private void verifySuccess(JsonNode body) {
        JsonNode common = body.path("results").path("common");
        String code = common.path("errorCode").asString("");
        if (SUCCESS_CODE.equals(code)) {
            return;
        }
        String message = common.path("errorMessage").asString("");
        throw new JusoApiException(
                failureReason(code),
                "주소 API 원천 오류가 발생했습니다: code=" + code + ", " + message,
                null,
                TOO_MANY_REQUESTS_CODE.equals(code)
        );
    }

    private RoadAddressGeocodingFailureReason failureReason(String code) {
        if (TOO_MANY_REQUESTS_CODE.equals(code)) {
            return RATE_LIMIT_EXCEEDED;
        }
        return EXTERNAL_API_ERROR;
    }

    private String requiredText(JsonNode row, String fieldName) {
        String value = row.path(fieldName).asString("");
        if (value.isBlank()) {
            throw externalFailure("주소 API 응답에 " + fieldName + " 값이 없습니다.");
        }
        return value;
    }

    private void requireConfigured(String key, String apiName) {
        if (key == null || key.isBlank()) {
            throw new JusoApiException(NOT_CONFIGURED, apiName + " 승인키가 설정되지 않았습니다.");
        }
    }

    private JusoApiException externalFailure(String message) {
        return new JusoApiException(EXTERNAL_API_ERROR, message);
    }

    private void sleep(Duration duration) {
        try {
            sleeper.sleep(duration);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JusoApiException(
                    EXTERNAL_API_ERROR,
                    "주소 API 재시도 대기가 중단되었습니다.",
                    exception
            );
        }
    }

    @FunctionalInterface
    interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;
    }
}
