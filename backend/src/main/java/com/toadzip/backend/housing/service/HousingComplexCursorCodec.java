package com.toadzip.backend.housing.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.exception.InvalidComplexCursorException;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor.DateValue;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor.DecimalValue;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor.SortValue;

public final class HousingComplexCursorCodec {

    private static final String V1 = "v1";

    private static final String V2 = "v2";

    private static final String NULL_POSTED_DATE = "~";

    public String encode(ComplexSummaryCursor cursor) {
        requireValidCursor(cursor);
        String payload = String.join(
                "|",
                V2,
                cursor.sort().name(),
                nullMarker(cursor.primaryValue()),
                encodedValue(cursor.primaryValue()),
                Long.toString(cursor.complexId())
        );
        return encodePayload(payload);
    }

    public ComplexSummaryCursor decode(String rawCursor, ComplexSort requestedSort) {
        if (rawCursor == null || rawCursor.isBlank() || requestedSort == null) {
            throw new InvalidComplexCursorException();
        }
        requireUnpaddedUrlSafeBase64(rawCursor);
        try {
            String payload = new String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", -1);
            if (parts.length == 5 && V2.equals(parts[0])) {
                return decodeV2(parts, requestedSort);
            }
            if (parts.length == 3 && V1.equals(parts[0])) {
                return decodeV1(parts, requestedSort);
            }
            throw new InvalidComplexCursorException();
        } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException exception) {
            throw new InvalidComplexCursorException();
        }
    }

    public String encode(LocalDate postedDate, long complexId) {
        return encode(new ComplexSummaryCursor(postedDate, complexId));
    }

    public HousingComplexCursor decode(String cursor) {
        ComplexSummaryCursor decoded = decode(cursor, ComplexSort.LATEST_ANNOUNCEMENT);
        return new HousingComplexCursor(decoded.postedDate(), decoded.complexId());
    }

    public record HousingComplexCursor(LocalDate postedDate, long complexId) {
    }

    private ComplexSummaryCursor decodeV2(String[] parts, ComplexSort requestedSort) {
        ComplexSort cursorSort = ComplexSort.valueOf(parts[1]);
        if (cursorSort != requestedSort) {
            throw new InvalidComplexCursorException();
        }
        SortValue primaryValue = decodePrimaryValue(cursorSort, parts[2], parts[3]);
        long complexId = decodeComplexId(parts[4]);
        return new ComplexSummaryCursor(cursorSort, primaryValue, complexId);
    }

    private ComplexSummaryCursor decodeV1(String[] parts, ComplexSort requestedSort) {
        if (requestedSort != ComplexSort.LATEST_ANNOUNCEMENT) {
            throw new InvalidComplexCursorException();
        }
        SortValue primaryValue = decodeV1PostedDate(parts[1]);
        long complexId = decodeComplexId(parts[2]);
        return new ComplexSummaryCursor(ComplexSort.LATEST_ANNOUNCEMENT, primaryValue, complexId);
    }

    private SortValue decodePrimaryValue(ComplexSort sort, String nullMarker, String rawValue) {
        if ("1".equals(nullMarker) && NULL_POSTED_DATE.equals(rawValue)) {
            return null;
        }
        if (!"0".equals(nullMarker) || rawValue.isEmpty() || NULL_POSTED_DATE.equals(rawValue)) {
            throw new InvalidComplexCursorException();
        }
        return switch (sort) {
            case LATEST_ANNOUNCEMENT, COMPLETION_DATE_DESC -> new DateValue(LocalDate.parse(rawValue));
            case DEPOSIT_ASC, MONTHLY_RENT_ASC, AREA_DESC -> decodeDecimalValue(rawValue);
        };
    }

    private SortValue decodeV1PostedDate(String rawPostedDate) {
        if (NULL_POSTED_DATE.equals(rawPostedDate)) {
            return null;
        }
        return new DateValue(LocalDate.parse(rawPostedDate));
    }

    private DecimalValue decodeDecimalValue(String rawValue) {
        BigDecimal value = new BigDecimal(rawValue);
        if (value.signum() < 0) {
            throw new InvalidComplexCursorException();
        }
        return new DecimalValue(value);
    }

    private long decodeComplexId(String rawComplexId) {
        long complexId = Long.parseLong(rawComplexId);
        if (complexId <= 0) {
            throw new InvalidComplexCursorException();
        }
        return complexId;
    }

    private void requireValidCursor(ComplexSummaryCursor cursor) {
        if (cursor == null || cursor.sort() == null || cursor.complexId() <= 0) {
            throw new InvalidComplexCursorException();
        }
        if (cursor.primaryValue() == null) {
            return;
        }
        requireValueType(cursor.sort(), cursor.primaryValue());
    }

    private void requireValueType(ComplexSort sort, SortValue value) {
        switch (sort) {
            case LATEST_ANNOUNCEMENT, COMPLETION_DATE_DESC -> requireDateValue(value);
            case DEPOSIT_ASC, MONTHLY_RENT_ASC, AREA_DESC -> requireDecimalValue(value);
        }
    }

    private void requireDateValue(SortValue value) {
        if (!(value instanceof DateValue)) {
            throw new InvalidComplexCursorException();
        }
    }

    private void requireDecimalValue(SortValue value) {
        if (!(value instanceof DecimalValue decimalValue) || decimalValue.value().signum() < 0) {
            throw new InvalidComplexCursorException();
        }
    }

    private String nullMarker(SortValue primaryValue) {
        if (primaryValue == null) {
            return "1";
        }
        return "0";
    }

    private String encodedValue(SortValue primaryValue) {
        if (primaryValue == null) {
            return NULL_POSTED_DATE;
        }
        return primaryValue.encodedValue();
    }

    private String encodePayload(String payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private void requireUnpaddedUrlSafeBase64(String cursor) {
        if (cursor.contains("=")) {
            throw new InvalidComplexCursorException();
        }
        boolean hasOnlyUrlSafeAlphabet = cursor.chars().allMatch(this::isUrlSafeBase64Character);
        if (!hasOnlyUrlSafeAlphabet) {
            throw new InvalidComplexCursorException();
        }
    }

    private boolean isUrlSafeBase64Character(int character) {
        if (character >= 'A' && character <= 'Z') {
            return true;
        }
        if (character >= 'a' && character <= 'z') {
            return true;
        }
        if (character >= '0' && character <= '9') {
            return true;
        }
        return character == '-' || character == '_';
    }
}
