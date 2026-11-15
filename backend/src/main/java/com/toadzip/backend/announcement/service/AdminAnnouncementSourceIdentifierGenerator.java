package com.toadzip.backend.announcement.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AdminAnnouncementSourceIdentifierGenerator {

    private static final String ANNOUNCEMENT_PREFIX = "ADMIN_ENTRY-ANNOUNCEMENT-";

    public String generateAnnouncementIdentifier() {
        return ANNOUNCEMENT_PREFIX + UUID.randomUUID();
    }
}
