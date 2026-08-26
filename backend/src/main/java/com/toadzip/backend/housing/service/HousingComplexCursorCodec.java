package com.toadzip.backend.housing.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import com.toadzip.backend.housing.exception.InvalidComplexCursorException;

public final class HousingComplexCursorCodec {

    private static final String VERSION = "v1";

    private static final String NULL_POSTED_DATE = "~";

    public String encode(LocalDate postedDate, long complexId) {
        String payload = VERSION + "|" + encodePostedDate(postedDate) + "|" + complexId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public HousingComplexCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new InvalidComplexCursorException();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new InvalidComplexCursorException();
            }
            return new HousingComplexCursor(decodePostedDate(parts[1]), Long.parseLong(parts[2]));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new InvalidComplexCursorException();
        }
    }

    public record HousingComplexCursor(LocalDate postedDate, long complexId) {
    }

    private String encodePostedDate(LocalDate postedDate) {
        if (postedDate == null) {
            return NULL_POSTED_DATE;
        }
        return postedDate.toString();
    }

    private LocalDate decodePostedDate(String rawPostedDate) {
        if (NULL_POSTED_DATE.equals(rawPostedDate)) {
            return null;
        }
        return LocalDate.parse(rawPostedDate);
    }
}
