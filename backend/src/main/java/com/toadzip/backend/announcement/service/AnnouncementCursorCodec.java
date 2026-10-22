package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.AnnouncementSort;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementCursorException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AnnouncementCursorCodec {

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern POSITIVE_ID_PATTERN = Pattern.compile("[1-9]\\d*");

    public String encode(LocalDate postedDate, long id) {
        return encode(postedDate, id, AnnouncementSort.LATEST);
    }

    public String encode(LocalDate postedDate, long id, AnnouncementSort sort) {
        validateCursorValues(postedDate, id);
        String payload = version(sort) + "|" + postedDate + "|" + id;
        return encodePayload(payload.getBytes(StandardCharsets.UTF_8));
    }

    public AnnouncementCursor decode(String encodedCursor) {
        return decode(encodedCursor, AnnouncementSort.LATEST);
    }

    public AnnouncementCursor decode(String encodedCursor, AnnouncementSort sort) {
        String payload = decodePayload(encodedCursor);
        String[] parts = payload.split("\\|", -1);
        validateParts(parts, sort);
        LocalDate postedDate = parseDate(parts[1]);
        long id = parseId(parts[2]);
        return new AnnouncementCursor(postedDate, id);
    }

    public String encodeDeadline(LocalDate asOf, int priority, LocalDate sortDate, LocalDate postedDate, long id) {
        return encodeDeadline(asOf, priority, sortDate, postedDate, id, AnnouncementSort.DEADLINE);
    }

    public String encodeDeadline(LocalDate asOf, int priority, LocalDate sortDate,
            LocalDate postedDate, long id, AnnouncementSort sort) {
        validateCursorValues(asOf, id);
        validateCursorValues(sortDate, id);
        validateCursorValues(postedDate, id);
        if (priority < 0 || priority > 3) {
            throw invalidCursor();
        }
        String payload = orderedVersion(sort) + "|" + asOf + "|" + priority + "|" + sortDate
                + "|" + postedDate + "|" + id;
        return encodePayload(payload.getBytes(StandardCharsets.UTF_8));
    }

    public DeadlineCursor decodeDeadline(String encodedCursor) {
        return decodeDeadline(encodedCursor, AnnouncementSort.DEADLINE);
    }

    public DeadlineCursor decodeDeadline(String encodedCursor, AnnouncementSort sort) {
        String[] parts = decodePayload(encodedCursor).split("\\|", -1);
        if (parts.length != 6 || !orderedVersion(sort).equals(parts[0]) || !parts[2].matches("[0-3]")
                || !DATE_PATTERN.matcher(parts[1]).matches() || !DATE_PATTERN.matcher(parts[3]).matches()
                || !DATE_PATTERN.matcher(parts[4]).matches() || !POSITIVE_ID_PATTERN.matcher(parts[5]).matches()) {
            throw invalidCursor();
        }
        return new DeadlineCursor(parseDate(parts[1]), Integer.parseInt(parts[2]),
                parseDate(parts[3]), parseDate(parts[4]), parseId(parts[5]));
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

    private String version(AnnouncementSort sort) {
        return switch (sort) {
            case LATEST -> "v1";
            case LATEST_ASC -> "v1-asc";
            case DEADLINE -> "v2";
            case DEADLINE_DESC -> "v2-desc";
        };
    }

    public boolean isLegacyLatestCursor(String cursor, AnnouncementSort sort) {
        return decodePayload(cursor).startsWith(version(sort) + "|");
    }

    private String orderedVersion(AnnouncementSort sort) {
        return switch (sort) {
            case LATEST -> "v3";
            case LATEST_ASC -> "v3-asc";
            case DEADLINE -> "v2";
            case DEADLINE_DESC -> "v2-desc";
        };
    }

    private void validateParts(String[] parts, AnnouncementSort sort) {
        if (parts.length != 3 || !version(sort).equals(parts[0])) {
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

    public record DeadlineCursor(LocalDate asOf, int priority, LocalDate sortDate, LocalDate postedDate, long id) {
    }
}
