package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.ScheduleRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.SupplyRowRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementImportValidationResponse;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementImportValidationResponse.FieldIssueResponse;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementImportValidationResponse.SupplyRowMatchResponse;
import com.toadzip.backend.announcement.dto.response.ImportComplexCandidateResponse;
import com.toadzip.backend.announcement.repository.AdminAnnouncementImportRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAnnouncementImportValidationService {

    private final HousingComplexRepository housingComplexRepository;
    private final AdminAnnouncementImportRepository importRepository;
    private final AnnouncementRepository announcementRepository;
    private final AdminAnnouncementImportJson importJson;
    private final Validator validator;

    public AdminAnnouncementImportValidationService(
            HousingComplexRepository housingComplexRepository,
            AdminAnnouncementImportRepository importRepository,
            AnnouncementRepository announcementRepository,
            AdminAnnouncementImportJson importJson,
            Validator validator
    ) {
        this.housingComplexRepository = housingComplexRepository;
        this.importRepository = importRepository;
        this.announcementRepository = announcementRepository;
        this.importJson = importJson;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public AdminAnnouncementImportValidationResponse validate(AdminAnnouncementImportRequest request) {
        String originalJson = importJson.serialize(request);
        String jsonHash = importJson.hash(originalJson);
        List<FieldIssueResponse> errors = new ArrayList<>(validationErrors(request));
        if (!importJson.isWithinSizeLimit(originalJson)) {
            errors.add(new FieldIssueResponse("$", "JSON은 UTF-8 기준 1,000,000바이트 이하여야 합니다."));
        }
        errors.addAll(validatePeriods(request));
        List<SupplyRowMatchResponse> matches = matchSupplyRows(request);
        boolean duplicated = isDuplicated(request, jsonHash);
        List<FieldIssueResponse> warnings = duplicateWarnings(duplicated);
        boolean registerable = errors.isEmpty()
                && unresolvedFields(request).isEmpty()
                && matches.stream().allMatch(this::hasCandidate)
                && !duplicated;
        return new AdminAnnouncementImportValidationResponse(
                schemaVersionOf(request),
                jsonHash,
                registerable,
                duplicated,
                errors,
                warnings,
                unresolvedFields(request),
                matches
        );
    }

    private List<FieldIssueResponse> validationErrors(AdminAnnouncementImportRequest request) {
        return validator.validate(request).stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(this::fieldIssueOf)
                .toList();
    }

    private FieldIssueResponse fieldIssueOf(ConstraintViolation<AdminAnnouncementImportRequest> violation) {
        return new FieldIssueResponse(violation.getPropertyPath().toString(), violation.getMessage());
    }

    private List<FieldIssueResponse> validatePeriods(AdminAnnouncementImportRequest request) {
        List<FieldIssueResponse> errors = new ArrayList<>();
        if (hasInvalidApplicationPeriod(request)) {
            errors.add(new FieldIssueResponse(
                    "announcement.applicationEndDate",
                    "접수 종료일은 접수 시작일보다 빠를 수 없습니다."
            ));
        }
        if (request.schedules() == null) {
            return List.copyOf(errors);
        }
        for (int index = 0; index < request.schedules().size(); index++) {
            ScheduleRequest schedule = request.schedules().get(index);
            if (schedule != null && schedule.startAt() != null && schedule.endAt() != null
                    && schedule.endAt().isBefore(schedule.startAt())) {
                errors.add(new FieldIssueResponse(
                        "schedules[" + index + "].endAt",
                        "일정 종료일시는 시작일시보다 빠를 수 없습니다."
                ));
            }
        }
        return List.copyOf(errors);
    }

    private boolean hasInvalidApplicationPeriod(AdminAnnouncementImportRequest request) {
        if (request.announcement() == null
                || request.announcement().applicationStartDate() == null
                || request.announcement().applicationEndDate() == null) {
            return false;
        }
        return request.announcement().applicationEndDate()
                .isBefore(request.announcement().applicationStartDate());
    }

    private List<SupplyRowMatchResponse> matchSupplyRows(AdminAnnouncementImportRequest request) {
        List<SupplyRowMatchResponse> matches = new ArrayList<>();
        if (request.supplyRows() == null) {
            return List.of();
        }
        for (int index = 0; index < request.supplyRows().size(); index++) {
            SupplyRowRequest supplyRow = request.supplyRows().get(index);
            if (canMatch(supplyRow, request)) {
                matches.add(matchSupplyRow(index, supplyRow, request));
            }
        }
        return List.copyOf(matches);
    }

    private boolean canMatch(SupplyRowRequest supplyRow, AdminAnnouncementImportRequest request) {
        return supplyRow != null
                && supplyRow.complexReference() != null
                && supplyRow.complexReference().sourceComplexName() != null
                && supplyRow.complexReference().pnu() != null
                && request.announcement() != null
                && request.announcement().rentalType() != null;
    }

    private SupplyRowMatchResponse matchSupplyRow(
            int index,
            SupplyRowRequest supplyRow,
            AdminAnnouncementImportRequest request
    ) {
        String sourceName = supplyRow.complexReference().sourceComplexName();
        List<HousingComplex> complexes = housingComplexRepository.findAllByPnuAndSupplyType(
                supplyRow.complexReference().pnu(),
                request.announcement().rentalType().name()
        );
        List<ImportComplexCandidateResponse> candidates = complexes.stream()
                .sorted(candidateComparator(sourceName))
                .map(this::candidateOf)
                .toList();
        Long suggestedId = candidates.size() == 1 ? candidates.getFirst().housingComplexId() : null;
        return new SupplyRowMatchResponse(
                index,
                sourceName,
                supplyRow.complexReference().pnu(),
                matchStatus(candidates.size()),
                suggestedId,
                candidates
        );
    }

    private Comparator<HousingComplex> candidateComparator(String sourceName) {
        return Comparator.comparing((HousingComplex complex) -> !complex.getName().equals(sourceName))
                .thenComparing(HousingComplex::getName)
                .thenComparing(HousingComplex::getId);
    }

    private ImportComplexCandidateResponse candidateOf(HousingComplex complex) {
        return new ImportComplexCandidateResponse(
                complex.getId(),
                complex.getName(),
                complex.getAddress().getRoadAddress(),
                complex.getSupplyType(),
                complex.getProvider()
        );
    }

    private String matchStatus(int candidateCount) {
        if (candidateCount == 0) {
            return "NOT_FOUND";
        }
        if (candidateCount == 1) {
            return "AUTO_SELECTED";
        }
        return "SELECTION_REQUIRED";
    }

    private boolean isDuplicated(AdminAnnouncementImportRequest request, String jsonHash) {
        if (importRepository.existsByJsonHash(jsonHash)) {
            return true;
        }
        if (request.source() == null) {
            return false;
        }
        if (request.source().originalUrl() != null
                && announcementRepository.existsByOriginalUrl(request.source().originalUrl())) {
            return true;
        }
        String sourceDocumentId = request.source().sourceDocumentId();
        return sourceDocumentId != null && importRepository.existsBySourceDocumentId(sourceDocumentId);
    }

    private List<FieldIssueResponse> duplicateWarnings(boolean duplicated) {
        if (!duplicated) {
            return List.of();
        }
        return List.of(new FieldIssueResponse("source.sourceDocumentId", "이미 등록된 원천 공고 또는 JSON입니다."));
    }

    private boolean hasCandidate(SupplyRowMatchResponse match) {
        return !match.candidates().isEmpty();
    }

    private List<AdminAnnouncementImportRequest.UnresolvedFieldRequest> unresolvedFields(
            AdminAnnouncementImportRequest request
    ) {
        if (request.unresolvedFields() == null) {
            return List.of();
        }
        return request.unresolvedFields();
    }

    private String schemaVersionOf(AdminAnnouncementImportRequest request) {
        if (request.schemaVersion() == null) {
            return "";
        }
        return request.schemaVersion();
    }
}
