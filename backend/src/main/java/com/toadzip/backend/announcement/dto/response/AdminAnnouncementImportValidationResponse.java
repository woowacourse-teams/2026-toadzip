package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.UnresolvedFieldRequest;
import java.util.List;

public record AdminAnnouncementImportValidationResponse(
        String schemaVersion,
        String jsonHash,
        boolean registerable,
        boolean duplicated,
        List<FieldIssueResponse> errors,
        List<FieldIssueResponse> warnings,
        List<UnresolvedFieldRequest> unresolvedFields,
        List<SupplyRowMatchResponse> supplyRows
) {

    public AdminAnnouncementImportValidationResponse {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
        unresolvedFields = List.copyOf(unresolvedFields);
        supplyRows = List.copyOf(supplyRows);
    }

    public record FieldIssueResponse(String path, String reason) {
    }

    public record SupplyRowMatchResponse(
            int supplyRowIndex,
            String sourceComplexName,
            String pnu,
            String status,
            Long suggestedHousingComplexId,
            List<ImportComplexCandidateResponse> candidates
    ) {
        public SupplyRowMatchResponse {
            candidates = List.copyOf(candidates);
        }
    }

}
