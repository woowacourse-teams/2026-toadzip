package com.toadzip.backend.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SocialUserLockRepository {

    private final JdbcTemplate jdbcTemplate;

    public void lockIdentifier(String identifier) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                result -> { }, "social-login:" + identifier);
    }
}
