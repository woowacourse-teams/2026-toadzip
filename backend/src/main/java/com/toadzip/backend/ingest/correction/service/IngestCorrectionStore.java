package com.toadzip.backend.ingest.correction.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.correction.domain.IngestCorrection;
import com.toadzip.backend.ingest.correction.domain.IngestCorrectionChange;
import com.toadzip.backend.ingest.correction.dto.IngestCorrectionDetail;
import com.toadzip.backend.ingest.correction.dto.IngestCorrectionRequest;
import com.toadzip.backend.ingest.correction.repository.IngestCorrectionRepository;
import com.toadzip.backend.ingest.correction.repository.IngestCorrectionChangeRepository;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.service.IngestCorrectionOverlay;
import com.toadzip.backend.ingest.mapping.service.IngestCorrectionSources;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IngestCorrectionStore {
    private final IngestCorrectionRepository corrections;
    private final IngestCorrectionChangeRepository changes;
    private final IngestCorrectionSources sources;
    private final IngestCorrectionOverlay overlay;
    private final HousingComplexRepository complexes;
    private final AnnouncementRepository announcements;
    private final MyHomeComplexLinkRepository links;
    private final ObjectMapper json;

    public IngestCorrectionDetail detail(String domain, String identifier) {
        var editable = overlay.fields(domain);
        var stored = corrections.findById(key(domain, identifier)).orElse(null);
        IngestCorrectionRequest payload = null;
        if (stored != null) {
            payload = json.readValue(stored.getPayload(), IngestCorrectionRequest.class);
        }
        List<IngestCorrectionRequest.Row> patches = List.of();
        if (payload != null) {
            patches = payload.rows();
        }
        Product product = product(domain, identifier);
        List<IngestCorrectionDetail.Row> original = List.of();
        boolean manualComplex = domain.equals("complex") && product.id() != null
                && !identifier.matches("[0-9]+:[A-Z0-9_]+");
        if (!manualComplex) {
            original = sources.rows(domain, identifier, List.of());
        }
        // 원천 교체로 사라진 보완 행은 자동 적용하지 않는다. 관리자가 새 원천을 다시 확인한다.
        var keys = original.stream().map(IngestCorrectionDetail.Row::sourceKey).toList();
        List<IngestCorrectionDetail.Row> current = List.of();
        if (!original.isEmpty()) {
            current = sources.rows(domain, identifier,
                    patches.stream().filter(row -> keys.contains(row.sourceKey())).toList());
        }
        var history = changes.findByTargetOrderByIdDesc(key(domain, identifier), PageRequest.of(0, 20)).stream()
                .map(change -> new IngestCorrectionDetail.Change(change.getActor(), change.getOccurredAt(),
                        change.getBeforeValue(), change.getAfterValue())).toList();
        String version = "none";
        if (stored != null) {
            version = stored.getVersion() + ":" + stored.getUpdatedAt();
        }
        String token = digest(json.writeValueAsString(original) + ":" + product.version() + ":" + version);
        java.math.BigDecimal latitude = null;
        java.math.BigDecimal longitude = null;
        if (payload != null) {
            latitude = payload.latitude();
            longitude = payload.longitude();
        }
        return new IngestCorrectionDetail(domain, identifier, token, product.id(),
                product.protectedValue() || current.isEmpty(), editable, current,
                latitude, longitude, history);
    }

    @Transactional
    public void save(String domain, String identifier, IngestCorrectionRequest request, String actor) {
        var current = detail(domain, identifier);
        if (!current.token().equals(request.token())) {
            throw new AdminDataConflictException("원천 또는 데이터가 변경되었습니다. 다시 조회한 뒤 보완해 주세요.");
        }
        if (current.managementOnly()) {
            throw new AdminDataConflictException("관리자 수정값 또는 통합 단지는 기존 데이터 관리에서 수정해 주세요.");
        }
        if (domain.equals("complex")) {
            sources.complexes(identifier, request.rows());
        }
        if (domain.equals("announcement")) {
            sources.announcements(identifier, request.rows());
        }
        if ((request.latitude() == null) != (request.longitude() == null)) {
            throw new InvalidIngestRequestException("위도와 경도를 함께 입력해 주세요.");
        }
        String target = key(domain, identifier);
        var correction = corrections.findById(target).orElse(null);
        String before = "{}";
        String after = json.writeValueAsString(request);
        if (correction == null) {
            correction = IngestCorrection.create(target, after, actor);
        }
        else {
            before = correction.getPayload();
            correction.revise(after, actor);
        }
        corrections.saveAndFlush(correction);
        changes.save(new IngestCorrectionChange(target, actor, before, after));
    }

    @Transactional
    public void recordOutcome(String domain, String identifier, String failure) {
        corrections.findById(key(domain, identifier)).orElseThrow().recordOutcome(failure);
    }

    private Product product(String domain, String identifier) {
        if (domain.equals("complex")) {
            var link = links.findById(identifier).orElse(null);
            if (link != null && link.getMergeId() != null) {
                var value = link.getHousingComplex();
                return new Product(value.getId(), value.getVersion(), true);
            }
            return complexes.findBySourceComplexIdentifier(identifier)
                    .map(value -> new Product(value.getId(), value.getVersion(), value.isAdminModified()
                            || value.isAdminDeleted())).orElse(new Product(null, -1, false));
        }
        return announcements.findBySourceAnnouncementIdentifier(identifier)
                .map(value -> new Product(value.getId(), value.getVersion(), value.isAdminModified()
                        || value.isAdminDeleted())).orElse(new Product(null, -1, false));
    }

    private String key(String domain, String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 200) {
            throw new InvalidIngestRequestException("원천 식별자가 올바르지 않습니다.");
        }
        return domain + ":" + identifier;
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Product(Long id, long version, boolean protectedValue) {
    }
}
