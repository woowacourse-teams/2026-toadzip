package com.toadzip.backend.housing.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.housing.domain.ComplexVerificationField;
import com.toadzip.backend.housing.domain.ComplexVerificationStatus;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingComplexReview;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.ComplexReviewRequest;
import com.toadzip.backend.housing.dto.response.ComplexVerificationResponse;
import com.toadzip.backend.housing.dto.response.ComplexVerificationResponse.Review;
import com.toadzip.backend.housing.dto.response.ComplexVerificationResponse.Source;
import com.toadzip.backend.housing.exception.AdminHousingComplexNotFoundException;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingComplexReviewRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ComplexVerificationService {
    private final HousingComplexRepository complexes;
    private final HousingComplexReviewRepository reviews;
    private final MyHomeComplexLinkRepository links;
    private final MyHomeComplexSourceReader sources;
    private final ObjectMapper json;
    private final Clock clock;

    public ComplexVerificationService(HousingComplexRepository complexes, HousingComplexReviewRepository reviews,
            MyHomeComplexLinkRepository links, MyHomeComplexSourceReader sources, ObjectMapper json, Clock clock) {
        this.complexes = complexes;
        this.reviews = reviews;
        this.links = links;
        this.sources = sources;
        this.json = json;
        this.clock = clock;
    }

    public ComplexVerificationResponse detail(long id) {
        return response(complexes.findById(id).orElseThrow(AdminHousingComplexNotFoundException::new));
    }

    @Transactional
    public ComplexVerificationResponse review(long id, ComplexReviewRequest request, String actor) {
        HousingComplex complex = complexes.findByIdForUpdate(id)
                .orElseThrow(AdminHousingComplexNotFoundException::new);
        ComplexVerificationResponse current = response(complex);
        long latestId = 0;
        if (current.latestReview() != null) {
            latestId = current.latestReview().id();
        }
        if (complex.getVersion() != request.version() || latestId != request.reviewId()
                || !current.snapshotToken().equals(request.snapshotToken())) {
            throw new AdminDataConflictException("단지 정보 또는 검토 기록이 변경되었습니다. 새로 조회해 주세요.");
        }
        if (request.fields().stream().distinct().count() != request.fields().size()) {
            throw new IllegalArgumentException("확인 항목을 중복 선택할 수 없습니다.");
        }
        Map<String, Object> checked = new LinkedHashMap<>();
        request.fields().stream().sorted().forEach(field -> checked.put(field.name(),
                current.currentValues().get(field.name())));
        reviews.saveAndFlush(new HousingComplexReview(complex, request.outcome(), json.writeValueAsString(checked),
                evidenceUrl(request.evidenceUrl()), request.evidenceNote(), actor, clock.instant()));
        return response(complex);
    }

    private ComplexVerificationResponse response(HousingComplex complex) {
        Map<String, Object> current = values(reviews.currentValues(complex.getId()));
        List<Source> evidence = sources(complex);
        List<Review> history = reviews.findByHousingComplexIdOrderByIdDesc(complex.getId(), PageRequest.of(0, 20))
                .stream().map(this::reviewResponse).toList();
        Review latest = null;
        if (!history.isEmpty()) {
            latest = history.getFirst();
        }
        String status = reviews.currentStatuses(List.of(complex.getId())).getFirst().getStatus();
        return new ComplexVerificationResponse(complex.getVersion(), token(current, evidence),
                ComplexVerificationStatus.valueOf(status), current, evidence, latest, history);
    }

    private List<Source> sources(HousingComplex complex) {
        List<String> identifiers = new ArrayList<>(links.findAllByHousingComplexId(complex.getId()).stream()
                .map(link -> link.getSourceComplexIdentifier()).toList());
        if (identifiers.isEmpty()) {
            identifiers.add(complex.getSourceComplexIdentifier());
        }
        List<Source> result = new ArrayList<>();
        identifiers.stream().sorted().forEach(identifier -> addSources(identifier, result));
        return List.copyOf(result);
    }

    private void addSources(String identifier, List<Source> result) {
        if (!identifier.matches("[0-9]{1,18}:[A-Z0-9_]+")) {
            return;
        }
        String rentalCode = identifier.substring(identifier.indexOf(':') + 1);
        RentalType rental;
        try {
            rental = RentalType.valueOf(rentalCode);
        } catch (IllegalArgumentException invalidCode) {
            return;
        }
        long sourceId = Long.parseLong(identifier.substring(0, identifier.indexOf(':')));
        sources.findAllByHsmpSnIn(List.of(sourceId)).stream()
                .filter(row -> sameRentalType(row.getSuplyTyNm(), rental))
                .sorted(java.util.Comparator.comparing(row -> row.getId()))
                .forEach(row -> result.add(new Source(identifier, row.getHsmpNm(), row.getRnAdres(),
                        row.getPnu(), row.getInsttNm(), row.getSuplyTyNm(), row.getHshldCo(), row.getStyleNm(),
                        row.getSuplyPrvuseAr(), row.getCollectedAt())));
    }

    private boolean sameRentalType(String raw, RentalType rental) {
        if (rental == RentalType.PUBLIC_RENTAL_50Y) {
            return "50년임대".equals(raw);
        }
        return rental.legacyStoredValue().equals(raw);
    }

    private Review reviewResponse(HousingComplexReview review) {
        Map<String, Object> checked = values(review.getCheckedValues());
        List<ComplexVerificationField> fields = checked.keySet().stream()
                .map(ComplexVerificationField::valueOf).sorted().toList();
        return new Review(review.getId(), review.getOutcome(), fields, checked, review.getEvidenceUrl(),
                review.getEvidenceNote(), review.getActor(), review.getReviewedAt());
    }

    private Map<String, Object> values(String snapshot) {
        return json.readValue(snapshot, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private String token(Map<String, Object> current, List<Source> evidence) {
        String snapshot = json.writeValueAsString(List.of(current, evidence));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(snapshot.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String evidenceUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null
                || !List.of("http", "https").contains(Objects.toString(uri.getScheme(), "").toLowerCase(
                        java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("근거 URL은 올바른 HTTP 또는 HTTPS 주소여야 합니다.");
        }
        return value;
    }
}
