package com.toadzip.backend.admin.controller;

import com.toadzip.backend.housing.controller.AdminHousingComplexManagementController;
import com.toadzip.backend.announcement.controller.AdminAnnouncementManagementController;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(assignableTypes = {AdminHousingComplexManagementController.class,
        AdminAnnouncementManagementController.class})
public class AdminManagementValidationAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", exception.getMessage()));
    }
}
