package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminAnnouncementSourceIdentifierGeneratorTest {

    @Test
    void 공고의_관리자_수기_원천_식별자를_UUID로_생성한다() {
        AdminAnnouncementSourceIdentifierGenerator generator = new AdminAnnouncementSourceIdentifierGenerator();

        String announcementIdentifier = generator.generateAnnouncementIdentifier();

        assertIdentifier(announcementIdentifier, "ADMIN_ENTRY-ANNOUNCEMENT-");
    }

    private void assertIdentifier(String identifier, String prefix) {
        assertThat(identifier).startsWith(prefix);
        String uuidText = identifier.substring(prefix.length());
        assertEquals(uuidText, UUID.fromString(uuidText).toString());
    }
}
