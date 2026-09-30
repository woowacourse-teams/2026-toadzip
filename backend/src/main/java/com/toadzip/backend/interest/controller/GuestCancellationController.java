package com.toadzip.backend.interest.controller;

import com.toadzip.backend.interest.repository.GuestCancellationRepository.Request;
import com.toadzip.backend.interest.service.GuestCancellationService;
import com.toadzip.backend.interest.service.GuestCancellationService.IssuedCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class GuestCancellationController {

    private final GuestCancellationService service;

    @PostMapping("/api/v1/notification-guest-cancellations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void request(@Valid @RequestBody EmailRequest request) {
        service.request(request.email());
    }

    @PostMapping("/api/v1/notification-guest-cancellations/verify")
    public ResponseEntity<Void> verify(@Valid @RequestBody VerificationRequest request) {
        if (!service.verifyAndCancel(request.email(), request.code())) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/admin/notification-guest-cancellations")
    public List<Request> pending() {
        return service.pending();
    }

    @PostMapping("/api/admin/notification-guest-cancellations/{id}/code")
    public IssuedCode issue(@PathVariable UUID id) {
        return service.issue(id);
    }

    @PostMapping("/api/admin/notification-guest-cancellations/{id}/code/reissue")
    public IssuedCode reissue(@PathVariable UUID id) {
        return service.reissue(id);
    }

    @PostMapping("/api/admin/notification-guest-cancellations/{id}/sent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markSent(@PathVariable UUID id, Authentication authentication,
                         @Valid @RequestBody CodeSentRequest request) {
        service.markSent(id, authentication.getName(), request.code());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Void> handleIssueError(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).build();
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record CodeSentRequest(@NotBlank @Size(max = 128) String code) {
    }

    public record VerificationRequest(@NotBlank @Email @Size(max = 254) String email,
                                      @NotBlank @Size(max = 128) String code) {
    }
}
