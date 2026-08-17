package com.toadzip.backend.ingest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

public final class SourceValues {

	private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
		.withResolverStyle(ResolverStyle.STRICT);

	private static final DateTimeFormatter DOTTED_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd")
		.withResolverStyle(ResolverStyle.STRICT);

	private SourceValues() {
	}

	public static String trimToNull(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return raw.strip();
	}

	public static LocalDate toDate(String raw) {
		String value = trimToNull(raw);
		if (value == null) {
			return null;
		}

		DateTimeFormatter formatter = COMPACT_DATE;
		if (value.contains(".")) {
			formatter = DOTTED_DATE;
		}

		try {
			return LocalDate.parse(value, formatter);
		}
		catch (DateTimeParseException exception) {
			return null;
		}
	}

	public static Integer toInt(String raw) {
		String value = trimToNull(raw);
		if (value == null) {
			return null;
		}

		String digits = value.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) {
			return null;
		}

		try {
			return Integer.valueOf(digits);
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}

}
