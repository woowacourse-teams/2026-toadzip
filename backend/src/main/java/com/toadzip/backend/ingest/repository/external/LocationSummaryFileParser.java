package com.toadzip.backend.ingest.repository.external;

import com.toadzip.backend.ingest.domain.LocationSummaryRecord;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

@Component
public class LocationSummaryFileParser {

    private static final int COLUMN_COUNT = 18;

    private static final int CHARSET_SAMPLE_SIZE = 8_192;

    private static final int MAX_ENTRY_COUNT = 30;

    private static final long MAX_ROW_COUNT = 10_000_000;

    private static final Charset MS949 = Charset.forName("MS949");

    public LocationSummaryFileParseResult parse(
            InputStream input,
            Consumer<LocationSummaryRecord> consumer
    ) {
        if (input == null) {
            throw new InvalidIngestRequestException("위치정보요약DB 월전체 ZIP은 필수입니다.");
        }
        try {
            PushbackInputStream source = new PushbackInputStream(input, 4);
            byte[] signature = source.readNBytes(4);
            source.unread(signature);
            if (!isZip(signature)) {
                throw new InvalidIngestRequestException("위치정보요약DB 월전체 ZIP 형식이 아닙니다.");
            }
            return parseZip(source, consumer);
        }
        catch (IOException exception) {
            throw new InvalidIngestRequestException("위치정보요약DB 월전체 ZIP을 읽지 못했습니다.", exception);
        }
    }

    private LocationSummaryFileParseResult parseZip(
            InputStream input,
            Consumer<LocationSummaryRecord> consumer
    ) throws IOException {
        ZipInputStream zip = new ZipInputStream(input, MS949);
        ParseState state = new ParseState();
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (!isLocationSummaryEntry(entry)) {
                continue;
            }
            String entryName = baseName(entry.getName());
            state.startEntry(entryName);
            parseText(zip, entryName, consumer, state);
        }
        return state.result();
    }

    private void parseText(
            InputStream input,
            String entryName,
            Consumer<LocationSummaryRecord> consumer,
            ParseState state
    ) throws IOException {
        PushbackInputStream source = new PushbackInputStream(input, CHARSET_SAMPLE_SIZE);
        Charset charset = detectCharset(source);
        BufferedReader reader = new BufferedReader(new InputStreamReader(source, charset));
        int rowNumber = 0;
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            rowNumber++;
            if (line.isBlank()) {
                continue;
            }
            LocationSummaryRecord record = parseLine(stripBom(line), entryName, rowNumber);
            consumer.accept(record);
            state.accept(entryName, record);
        }
    }

    private LocationSummaryRecord parseLine(String line, String entryName, int rowNumber) {
        String[] columns = line.split(line.contains("|") ? "\\|" : "\\t", -1);
        if (columns.length != COLUMN_COUNT) {
            throw invalidRow(entryName, rowNumber, "컬럼은 " + COLUMN_COUNT + "개여야 합니다.");
        }
        try {
            return new LocationSummaryRecord(
                    text(columns[0]),
                    text(columns[1]),
                    text(columns[2]),
                    text(columns[3]),
                    text(columns[4]),
                    text(columns[5]),
                    text(columns[6]),
                    text(columns[7]),
                    text(columns[8]),
                    integer(columns[9], "건물본번"),
                    integer(columns[10], "건물부번"),
                    decimal(columns[16], "X좌표"),
                    decimal(columns[17], "Y좌표")
            );
        }
        catch (IllegalArgumentException exception) {
            throw invalidRow(entryName, rowNumber, exception.getMessage(), exception);
        }
    }

    private Charset detectCharset(PushbackInputStream source) throws IOException {
        byte[] sample = source.readNBytes(CHARSET_SAMPLE_SIZE);
        source.unread(sample);
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sample));
            return StandardCharsets.UTF_8;
        }
        catch (CharacterCodingException exception) {
            return MS949;
        }
    }

    private int integer(String value, String fieldName) {
        try {
            return Integer.parseInt(text(value));
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " 형식이 올바르지 않습니다.", exception);
        }
    }

    private BigDecimal decimal(String value, String fieldName) {
        String normalized = text(value);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " 형식이 올바르지 않습니다.", exception);
        }
    }

    private String text(String value) {
        if (value == null) {
            return "";
        }
        return value.strip();
    }

    private String stripBom(String line) {
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }

    private boolean isLocationSummaryEntry(ZipEntry entry) {
        if (entry.isDirectory()) {
            return false;
        }
        String name = baseName(entry.getName());
        return name.startsWith("entrc_") && name.endsWith(".txt");
    }

    private String baseName(String entryName) {
        int lastSeparator = entryName.lastIndexOf('/');
        return entryName.substring(lastSeparator + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isZip(byte[] signature) {
        return signature.length == 4
                && signature[0] == 'P'
                && signature[1] == 'K'
                && signature[2] == 3
                && signature[3] == 4;
    }

    private InvalidIngestRequestException invalidRow(
            String entryName,
            int rowNumber,
            String message,
            IllegalArgumentException cause
    ) {
        return new InvalidIngestRequestException(
                entryName + "의 " + rowNumber + "번째 행이 올바르지 않습니다: " + message,
                cause
        );
    }

    private InvalidIngestRequestException invalidRow(String entryName, int rowNumber, String message) {
        return new InvalidIngestRequestException(
                entryName + "의 " + rowNumber + "번째 행이 올바르지 않습니다: " + message
        );
    }

    private static final class ParseState {

        private int entryCount;

        private long rowCount;

        private long coordinateRowCount;

        private final Set<String> entryNames = new HashSet<>();

        private final Map<String, Set<String>> provinceCodesByEntry = new HashMap<>();

        private final Set<String> provinceCodes = new HashSet<>();

        private void startEntry(String entryName) {
            entryCount++;
            if (entryCount > MAX_ENTRY_COUNT) {
                throw new InvalidIngestRequestException("위치정보요약DB TXT 파일이 너무 많습니다.");
            }
            if (!entryNames.add(entryName)) {
                throw new InvalidIngestRequestException("위치정보요약DB TXT 파일명이 중복되었습니다: " + entryName);
            }
            provinceCodesByEntry.put(entryName, new HashSet<>());
        }

        private void accept(String entryName, LocationSummaryRecord record) {
            rowCount++;
            if (rowCount > MAX_ROW_COUNT) {
                throw new InvalidIngestRequestException("위치정보요약DB 행 수가 허용 범위를 초과했습니다.");
            }
            if (record.hasCoordinate()) {
                coordinateRowCount++;
            }
            provinceCodes.add(record.provinceCode());
            provinceCodesByEntry.get(entryName).add(record.provinceCode());
        }

        private LocationSummaryFileParseResult result() {
            if (entryCount == 0 || rowCount == 0) {
                throw new InvalidIngestRequestException("ZIP에 위치정보요약DB TXT가 없습니다.");
            }
            return new LocationSummaryFileParseResult(
                    entryCount,
                    rowCount,
                    coordinateRowCount,
                    entryNames,
                    provinceCodesByEntry,
                    provinceCodes
            );
        }
    }
}
