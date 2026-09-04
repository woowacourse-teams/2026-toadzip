package com.toadzip.backend.announcement.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AdminAnnouncementSourceIdentifierGenerator {

    private static final String ANNOUNCEMENT_PREFIX = "ADMIN_ENTRY-ANNOUNCEMENT-";

    private static final String SUPPLY_ROW_PREFIX = "ADMIN_ENTRY-SUPPLY-ROW-";

    public String generateAnnouncementIdentifier() {
        return ANNOUNCEMENT_PREFIX + UUID.randomUUID();
    }

    public String generateSupplyRowIdentifier() {
        return SUPPLY_ROW_PREFIX + UUID.randomUUID();
    }
}
