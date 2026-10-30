package com.toadzip.backend.interest.controller;

import com.toadzip.backend.interest.domain.BookmarkType;
import com.toadzip.backend.interest.dto.BookmarkResponse;
import com.toadzip.backend.interest.service.BookmarkService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookmarks/me")
@RequiredArgsConstructor
public class BookmarkController {
    private final BookmarkService service;

    @GetMapping
    public BookmarkResponse list(Authentication authentication) {
        return service.findForUser(Long.parseLong(authentication.getName()));
    }

    @PutMapping("/{type}/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(Authentication authentication, @PathVariable BookmarkType type,
            @PathVariable @Positive long targetId) {
        service.save(Long.parseLong(authentication.getName()), type, targetId);
    }

    @DeleteMapping("/{type}/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication authentication, @PathVariable BookmarkType type,
            @PathVariable @Positive long targetId) {
        service.remove(Long.parseLong(authentication.getName()), type, targetId);
    }
}
