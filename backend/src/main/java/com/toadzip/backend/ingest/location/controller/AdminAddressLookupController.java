package com.toadzip.backend.ingest.location.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.ingest.location.dto.AddressCandidate;
import com.toadzip.backend.ingest.location.exception.AddressLookupException;
import com.toadzip.backend.ingest.location.service.AddressLookupService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/addresses")
public class AdminAddressLookupController {

    private final AddressLookupService service;

    @GetMapping
    public ApiResponse<List<AddressCandidate>> lookup(@RequestParam String keyword) {
        if (keyword.isBlank() || keyword.length() > 200) {
            throw new AddressLookupException("검색어는 1~200자로 입력해 주세요.", false);
        }
        return new ApiResponse<>(service.lookup(keyword));
    }

    @ExceptionHandler(AddressLookupException.class)
    public ResponseEntity<ErrorResponse> handleLookupFailure(AddressLookupException exception, HttpServletRequest request) {
        HttpStatus status = exception.isUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
        String code = exception.isUnavailable() ? "ADDRESS_LOOKUP_UNAVAILABLE" : "INVALID_ADDRESS_KEYWORD";
        return ResponseEntity.status(status).body(new ErrorResponse(code, exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }
}
