package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationInformation;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationInformationRequest;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationInformationResponse;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationFailuresResponse;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationTargetRepository;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationFailureRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 등록 전 이름 보완과 검증만 수행한다. 제품 저장과 원천 변경은 하지 않는다. */
@Service
@RequiredArgsConstructor
public class MyHomeInitialRegistrationSupplementService {
    private final InitialRegistrationTargetRepository targets;
    private final MyHomeComplexSourceReader sources;
    private final MyHomeComplexSourceMapper mapper;
    private final InitialRegistrationInputs inputs;
    private final MyHomeInitialRegistrationAssessment assessment;
    private final InitialRegistrationProductStore products;
    private final InitialRegistrationFailureRepository failures;
    private final IngestWriteOwnershipGuard ownership;
    private final Clock clock;

    @Transactional(readOnly = true)
    public InitialRegistrationInformationResponse find(String identifier) {
        var target = targets.findById(identifier).orElseThrow(() -> invalid("등록 대상을 찾을 수 없습니다."));
        var rows = rows(identifier);
        String fingerprint = inputs.fingerprint(rows);
        InitialRegistrationInformationResponse.Supplement supplement = null;
        var information = target.getInformation();
        if (inputs.hasInformation(information)) {
            supplement = new InitialRegistrationInformationResponse.Supplement(information.getComplexName(),
                    Map.copyOf(information.getHousingTypeNames()), information.getEvidence(),
                    information.getCheckedBy(),
                    information.getCheckedAt(), information.matches(fingerprint));
        }
        return new InitialRegistrationInformationResponse(fingerprint, rows.getFirst().getHsmpNm(),
                rows.stream().map(row -> new InitialRegistrationInformationResponse.HousingType(row.getSourceKey(),
                        row.getStyleNm(), row.getSuplyPrvuseAr())).toList(), supplement);
    }

    @Transactional
    public void supplement(InitialRegistrationInformationRequest request, String actor) {
        IngestExecutionScope.requireHeld();
        var target = targets.findForUpdate(request.sourceComplexIdentifier())
                .orElseThrow(() -> invalid("등록 대상을 찾을 수 없습니다."));
        if (target.isCompleted() || target.getStatus() == InitialRegistrationStatus.EXISTING
                || target.getHousingComplexId() != null) {
            throw invalid("기존 단지와 등록 완료 대상은 보완할 수 없습니다.");
        }
        var rows = rows(request.sourceComplexIdentifier());
        String fingerprint = inputs.fingerprint(rows);
        if (!fingerprint.equals(request.fingerprint())) {
            throw invalid("원천이 변경되었습니다. 정보를 다시 조회하고 근거를 확인하세요.");
        }
        var names = names(request.housingTypeNames(), rows);
        String complexName = normalized(request.complexName());
        if (complexName != null && rows.stream().noneMatch(row -> row.getHsmpNm() == null)) {
            throw invalid("원천에 있는 단지명은 최초 등록 보완으로 변경할 수 없습니다.");
        }
        target.supplementInformation(new InitialRegistrationInformation(fingerprint, complexName, names,
                request.evidence().strip(), actor, clock.instant()));
        var addresses = new java.util.ArrayList<>(rows.stream().map(MyHomeComplexSourceView::getRnAdres).toList());
        if (target.getLocation() != null) {
            addresses.add(target.getLocation().getAddress());
        }
        var candidateNames = new java.util.ArrayList<>(rows.stream().map(MyHomeComplexSourceView::getHsmpNm).toList());
        candidateNames.add(complexName);
        var index = new InitialRegistrationProductIndex(products.findRegistrationCandidates(
                request.sourceComplexIdentifier(), rows.stream().map(MyHomeComplexSourceView::getPnu).toList(),
                candidateNames, addresses),
                products.findLinks(request.sourceComplexIdentifier()));
        var result = assessment.assess(target, rows, index, clock.instant());
        target.assess(rows.getFirst().getHsmpNm(), rows.getFirst().getRnAdres(), result.status(), result.detail(),
                result.complexId(), clock.instant());
        ownership.verifyWrite();
        targets.saveAndFlush(target);
    }

    @Transactional(readOnly = true)
    public InitialRegistrationFailuresResponse failures(String identifier, int page) {
        var result = failures.findBySourceComplexIdentifierOrderByFailedAtDescIdDesc(
                identifier, PageRequest.of(page, 20));
        return new InitialRegistrationFailuresResponse(result.stream().map(failure ->
                new InitialRegistrationFailuresResponse.Failure(failure.getDetail(), failure.getFailedAt(),
                        failure.getActor(), failure.getExecutionId())).toList(), result.getTotalElements(),
                result.getTotalPages());
    }

    private Map<String, String> names(Map<String, String> requested, List<MyHomeComplexSourceView> rows) {
        Map<String, MyHomeComplexSourceView> available = rows.stream().collect(Collectors.toMap(
                MyHomeComplexSourceView::getSourceKey, row -> row, (first, second) -> first));
        Map<String, String> result = new LinkedHashMap<>();
        requested.forEach((key, value) -> {
            var row = available.get(key);
            if (row == null || row.getStyleNm() != null || normalized(value) == null) {
                throw invalid("현재 원천에 없거나 이미 이름이 있는 주택형은 보완할 수 없습니다.");
            }
            result.put(key, value.strip());
        });
        return result;
    }

    private List<MyHomeComplexSourceView> rows(String identifier) {
        if (identifier == null || !identifier.matches("[0-9]+:[A-Z_]+")) {
            throw invalid("원천 식별자가 올바르지 않습니다.");
        }
        long id;
        try {
            id = Long.parseLong(identifier.substring(0, identifier.indexOf(':')));
        }
        catch (NumberFormatException exception) {
            throw invalid("원천 식별자가 올바르지 않습니다.");
        }
        var result = sources.findAllByHsmpSnIn(List.of(id)).stream().filter(row -> !mapper.shouldSkip(row))
                .filter(row -> identifier.equals(identifier(row))).toList();
        if (result.isEmpty()) {
            throw invalid("현재 원천이 없습니다. 수집 이력에서 실패한 지역·요청을 확인하고 원천을 다시 수집하세요.");
        }
        return result;
    }

    private String identifier(MyHomeComplexSourceView row) {
        try {
            return mapper.sourceComplexIdentifier(row);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            return row.getHsmpSn() + ":UNKNOWN";
        }
    }

    private String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private InvalidIngestRequestException invalid(String message) {
        return new InvalidIngestRequestException(message);
    }
}
