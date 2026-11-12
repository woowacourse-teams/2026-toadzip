package com.toadzip.backend.ingest.mapping.controller;

import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationLocationRequest;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationLocationsResponse;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationResponse;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationRetryRequest;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationInformationRequest;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationInformationResponse;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationFailuresResponse;
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationSupplementService;
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/ingest/myhome/initial-registration")
public class MyHomeInitialRegistrationController {

    private final MyHomeInitialRegistrationService service;
    private final DataPipelineExecutionService execution;
    private final MyHomeInitialRegistrationSupplementService supplements;

    @GetMapping("/information")
    public InitialRegistrationInformationResponse information(@RequestParam String sourceComplexIdentifier) {
        return supplements.find(sourceComplexIdentifier);
    }

    @PostMapping("/information")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supplementInformation(@RequestBody @Valid InitialRegistrationInformationRequest request,
            Principal actor) {
        supplements.supplement(request, actor.getName());
    }

    @GetMapping("/failures")
    public InitialRegistrationFailuresResponse failures(@RequestParam String sourceComplexIdentifier,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return supplements.failures(sourceComplexIdentifier, page);
    }

    @PostMapping("/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DataPipelineExecutionResponse retry(@RequestBody @Valid InitialRegistrationRetryRequest request,
            Principal actor) {
        return execution.retryComplexRegistration(request.sourceComplexIdentifiers(), actor.getName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DataPipelineExecutionResponse register(Principal actor) {
        return execution.start(DataPipelineType.COMPLEX_REGISTRATION, actor.getName());
    }

    @GetMapping
    public InitialRegistrationResponse find(@RequestParam(required = false) InitialRegistrationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return service.find(status, page, size);
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh() {
        service.refresh();
    }

    @GetMapping("/locations")
    public InitialRegistrationLocationsResponse findLocations(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return service.findLocations(page, size);
    }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supplementLocation(@RequestBody @Valid InitialRegistrationLocationRequest request, Principal actor) {
        service.supplementLocation(request, actor.getName());
    }
}
