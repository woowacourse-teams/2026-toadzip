package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.exception.InvalidAnnouncementCursorException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AnnouncementCursorCodec {

    private static final String VERSION = "v1";
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern POSITIVE_ID_PATTERN = Pattern.compile("[1-9]\\d*");

    public String encode(LocalDate postedDate, long id) {
        validateCursorValues(postedDate, id);
        String payload = VERSION + "|" + postedDate + "|" + id;
        return encodePayload(payload.getBytes(StandardCharsets.UTF_8));
    }

    public AnnouncementCursor decode(String encodedCursor) {
        String payload = decodePayload(encodedCursor);
        String[] parts = payload.split("\\|", -1);
        validateParts(parts);
        LocalDate postedDate = parseDate(parts[1]);
        long id = parseId(parts[2]);
        return new AnnouncementCursor(postedDate, id);
    }

    private String decodePayload(String encodedCursor) {
        if (encodedCursor == null || encodedCursor.isBlank() || encodedCursor.contains("=")) {
            throw invalidCursor();
        }
        try {
            byte[] decodedPayload = Base64.getUrlDecoder().decode(encodedCursor);
            if (!encodedCursor.equals(encodePayload(decodedPayload))) {
                throw invalidCursor();
            }
            return new String(decodedPayload, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private String encodePayload(byte[] payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }

    private void validateParts(String[] parts) {
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw invalidCursor();
        }
        if (!DATE_PATTERN.matcher(parts[1]).matches() || !POSITIVE_ID_PATTERN.matcher(parts[2]).matches()) {
            throw invalidCursor();
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalidCursor();
        }
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalidCursor();
        }
    }

    private void validateCursorValues(LocalDate postedDate, long id) {
        if (postedDate == null || !DATE_PATTERN.matcher(postedDate.toString()).matches() || id <= 0) {
            throw invalidCursor();
        }
    }

    private InvalidAnnouncementCursorException invalidCursor() {
        return new InvalidAnnouncementCursorException();
    }

    public record AnnouncementCursor(LocalDate postedDate, long id) {
    }
}
