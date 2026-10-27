package com.toadzip.backend.interest.service;

import com.toadzip.backend.interest.domain.BookmarkType;
import com.toadzip.backend.interest.dto.BookmarkResponse;
import com.toadzip.backend.interest.exception.BookmarkTargetUnavailableException;
import com.toadzip.backend.interest.exception.BookmarkUserMissingException;
import com.toadzip.backend.interest.repository.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookmarkService {
    private final BookmarkRepository repository;

    @Transactional(readOnly = true)
    public BookmarkResponse findForUser(long userId) {
        return new BookmarkResponse(repository.findForUser(userId));
    }

    @Transactional
    public void save(long userId, BookmarkType type, long targetId) {
        lockUser(userId);
        if (!repository.save(userId, type, targetId)) {
            throw new BookmarkTargetUnavailableException();
        }
    }

    @Transactional
    public void remove(long userId, BookmarkType type, long targetId) {
        lockUser(userId);
        repository.remove(userId, type, targetId);
    }

    private void lockUser(long userId) {
        if (!repository.lockUser(userId)) {
            throw new BookmarkUserMissingException();
        }
    }
}
