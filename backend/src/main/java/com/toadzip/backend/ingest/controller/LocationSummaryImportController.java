package com.toadzip.backend.ingest.controller;

import com.toadzip.backend.ingest.dto.LocationSummaryImportReport;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.service.LocationSummaryImportService;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/ingest/juso/location-summaries")
public class LocationSummaryImportController {

    private final LocationSummaryImportService importService;

    public LocationSummaryImportController(LocationSummaryImportService importService) {
        this.importService = importService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LocationSummaryImportReport> importMatches(
            @RequestParam("file") MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new InvalidIngestRequestException("위치정보요약DB 월전체 ZIP은 필수입니다.");
        }
        try {
            LocationSummaryImportReport report = importService.importMatches(
                    file.getOriginalFilename(),
                    file.getInputStream()
            );
            return ResponseEntity.ok(report);
        }
        catch (IOException exception) {
            throw new InvalidIngestRequestException("위치정보요약DB 월전체 ZIP을 읽지 못했습니다.", exception);
        }
    }
}
