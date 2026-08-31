package com.toadzip.backend.ingest.service;

import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.ingest.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.domain.LhAnnouncementSupplySource;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LhAnnouncementEnrichmentMapper {

    private static final Pattern DATE_TIME = Pattern.compile(
            "(20\\d{2})\\D*(\\d{1,2})\\D*(\\d{1,2})(?:\\D+(\\d{1,2})\\D*(\\d{2}))?"
    );

    private static final Pattern YEAR_MONTH = Pattern.compile("((?:19|20)\\d{2})\\D*(\\d{1,2})");

    private static final int MAX_RECEPTION_NAME_LENGTH = 255;

    public LhAnnouncementEnrichmentData map(
            String panId,
            List<LhAnnouncementDetailSource> details,
            List<LhAnnouncementSupplySource> supplies
    ) {
        List<LhScheduleData> schedules = new ArrayList<>();
        List<LhAttachmentData> attachments = new ArrayList<>();
        List<LhComplexData> complexes = new ArrayList<>();
        String correctionReason = null;
        ReceptionPlace receptionPlace = null;
        for (LhAnnouncementDetailSource detail : details) {
            if ("ETC_INFO".equals(detail.getDatasetType()) && detail.getCorrectionReason() != null) {
                correctionReason = detail.getCorrectionReason();
            }
            if ("RECEPTION".equals(detail.getDatasetType()) && receptionPlace == null) {
                receptionPlace = receptionOf(detail);
            }
            if ("SCHEDULE".equals(detail.getDatasetType())) {
                schedules.addAll(schedulesOf(panId, detail));
            }
            if ("ANNOUNCEMENT_FILE".equals(detail.getDatasetType())) {
                attachments.add(attachmentOf(panId, detail));
            }
            if ("COMPLEX".equals(detail.getDatasetType())) {
                complexes.add(new LhComplexData(detail.getComplexName(), detail.getExpectedMoveInYearMonth()));
            }
        }
        return new LhAnnouncementEnrichmentData(
                panId, correctionReason, receptionPlace, schedules, attachments,
                supplies.stream()
                        .map(source -> supplyOf(panId, source, expectedMoveInMonthOf(source, complexes)))
                        .toList()
        );
    }

    private YearMonth expectedMoveInMonthOf(
            LhAnnouncementSupplySource supply,
            List<LhComplexData> complexes
    ) {
        List<LhComplexData> matches = complexes.stream()
                .filter(complex -> sameComplex(complex.name(), supply.getComplexLabel()))
                .toList();
        if (matches.size() == 1) {
            return yearMonthOf(matches.getFirst().expectedMoveInYearMonth(), "입주예정월");
        }
        if (matches.isEmpty() && complexes.size() == 1) {
            return yearMonthOf(complexes.getFirst().expectedMoveInYearMonth(), "입주예정월");
        }
        return null;
    }

    private boolean sameComplex(String left, String right) {
        return normalizedName(left).equals(normalizedName(right));
    }

    private String normalizedName(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").replace("-", "").strip().toLowerCase();
    }

    private ReceptionPlace receptionOf(LhAnnouncementDetailSource detail) {
        String address = joined(detail.getReceptionAddress(), detail.getReceptionDetailAddress());
        String name = textWithin(detail.getReceptionGuidance(), MAX_RECEPTION_NAME_LENGTH);
        if (name == null || name.isBlank()) {
            name = "LH 접수처";
        }
        return ReceptionPlace.create(name, ReceptionMethod.VISIT, address, detail.getPhone(), null);
    }

    private String textWithin(String value, int maximumLength) {
        if (value == null || value.length() > maximumLength) {
            return null;
        }
        return value;
    }

    private List<LhScheduleData> schedulesOf(String panId, LhAnnouncementDetailSource source) {
        List<LhScheduleData> schedules = new ArrayList<>();
        addRange(schedules, panId, source, ScheduleType.APPLICATION, "접수", source.getApplicationPeriod());
        addDate(schedules, panId, source, ScheduleType.WINNER_ANNOUNCEMENT, "당첨자 발표", source.getDocumentTargetAnnouncementDate());
        addRange(
                schedules, panId, source, ScheduleType.DOCUMENT_SUBMISSION, "서류제출",
                source.getDocumentSubmissionBeginDate(), source.getDocumentSubmissionEndDate()
        );
        addRange(schedules, panId, source, ScheduleType.CONTRACT, "계약", source.getContractBeginDate(), source.getContractEndDate());
        return schedules;
    }

    private void addRange(
            List<LhScheduleData> schedules,
            String panId,
            LhAnnouncementDetailSource source,
            ScheduleType type,
            String name,
            String range
    ) {
        if (unavailable(range)) {
            return;
        }
        List<LocalDateTime> values = dateTimes(range, name);
        if (values.size() < 2) {
            throw invalid(name + " 기간의 시작·종료 시각을 해석할 수 없습니다.");
        }
        schedules.add(schedule(panId, source, type, name, values.getFirst(), values.get(1)));
    }

    private void addRange(
            List<LhScheduleData> schedules,
            String panId,
            LhAnnouncementDetailSource source,
            ScheduleType type,
            String name,
            String begin,
            String end
    ) {
        if (unavailable(begin) && unavailable(end)) {
            return;
        }
        if (unavailable(begin) || unavailable(end)) {
            throw invalid(name + " 기간의 시작 또는 종료 시각이 없습니다.");
        }
        schedules.add(schedule(panId, source, type, name, dateTime(begin, name), dateTime(end, name)));
    }

    private void addDate(
            List<LhScheduleData> schedules,
            String panId,
            LhAnnouncementDetailSource source,
            ScheduleType type,
            String name,
            String value
    ) {
        if (!unavailable(value)) {
            LocalDateTime at = dateTime(value, name);
            schedules.add(schedule(panId, source, type, name, at, at));
        }
    }

    private LhScheduleData schedule(
            String panId,
            LhAnnouncementDetailSource source,
            ScheduleType type,
            String name,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        if (endAt.isBefore(startAt)) {
            throw invalid(name + " 종료 시각이 시작 시각보다 빠릅니다.");
        }
        return new LhScheduleData(
                identifier(panId, "SCHEDULE", source.getSourceOrder(), type.name()), type, name, startAt, endAt
        );
    }

    private LhAttachmentData attachmentOf(String panId, LhAnnouncementDetailSource source) {
        if (blank(source.getName()) || blank(source.getUrl())) {
            throw invalid("첨부파일명 또는 URL이 없습니다.");
        }
        return new LhAttachmentData(
                identifier(panId, "ANNOUNCEMENT_FILE", source.getSourceOrder(), null), source.getName(),
                attachmentTypeOf(source.getKind()), source.getUrl()
        );
    }

    private LhSupplyData supplyOf(String panId, LhAnnouncementSupplySource source, YearMonth expectedMoveInMonth) {
        if (blank(source.getComplexLabel()) || blank(source.getTypeName())) {
            throw invalid("LH 공급 원본의 단지명 또는 주택형명이 없습니다.");
        }
        return new LhSupplyData(
                identifier(panId, "SUPPLY", source.getSourceOrder(), null), source.getComplexLabel(),
                source.getTypeName(), expectedMoveInMonth, integerOf(source.getTotalUnitCount(), "전체 세대수"),
                integerOf(source.getSuppliedUnitCount(), "공급 세대수"), amountOf(source.getDepositText(), "임대보증금"),
                amountOf(source.getMonthlyRentText(), "월 임대료")
        );
    }

    private AttachmentType attachmentTypeOf(String kind) {
        if (kind != null && kind.contains("취소")) {
            return AttachmentType.CANCELLATION;
        }
        if (kind != null && kind.contains("정정")) {
            return AttachmentType.CORRECTION;
        }
        if (kind != null && kind.contains("공고")) {
            return AttachmentType.ANNOUNCEMENT;
        }
        return AttachmentType.REFERENCE;
    }

    private List<LocalDateTime> dateTimes(String value, String fieldName) {
        List<LocalDateTime> values = new ArrayList<>();
        Matcher matcher = DATE_TIME.matcher(value);
        while (matcher.find()) {
            values.add(dateTime(matcher, fieldName));
        }
        return values;
    }

    private LocalDateTime dateTime(String value, String fieldName) {
        Matcher matcher = DATE_TIME.matcher(value);
        if (!matcher.find()) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
        return dateTime(matcher, fieldName);
    }

    private LocalDateTime dateTime(Matcher matcher, String fieldName) {
        try {
            int hour = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
            int minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
            return LocalDateTime.of(
                    Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)), hour, minute
            );
        }
        catch (DateTimeException | NumberFormatException exception) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
    }

    private YearMonth yearMonthOf(String value, String fieldName) {
        if (unavailable(value)) {
            return null;
        }
        Matcher matcher = YEAR_MONTH.matcher(value);
        if (!matcher.find()) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
        try {
            return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
        catch (DateTimeException | NumberFormatException exception) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
    }

    private Integer integerOf(String value, String fieldName) {
        if (unavailable(value)) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
        try {
            return Integer.valueOf(digits);
        }
        catch (NumberFormatException exception) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
    }

    private BigDecimal amountOf(String value, String fieldName) {
        if (unavailable(value)) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
        return new BigDecimal(digits);
    }

    private String identifier(String panId, String datasetType, Integer sourceOrder, String suffix) {
        String identifier = "LH:" + panId + ":" + datasetType + ":" + sourceOrder;
        if (suffix == null) {
            return identifier;
        }
        return identifier + ":" + suffix;
    }

    private String joined(String first, String second) {
        if (blank(first)) {
            return second;
        }
        if (blank(second)) {
            return first;
        }
        return first + " " + second;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean unavailable(String value) {
        if (blank(value)) {
            return true;
        }
        String normalized = value.replaceAll("\\s+", "").strip();
        return normalized.equals("~")
                || normalized.equals("-")
                || normalized.startsWith("9999")
                || normalized.contains("공고문참조")
                || normalized.contains("미정")
                || normalized.contains("별도 안내");
    }

    private LhAnnouncementEnrichmentRejectedException invalid(String detail) {
        return new LhAnnouncementEnrichmentRejectedException(
                LhAnnouncementEnrichmentFailureReason.INVALID_VALUE, detail
        );
    }
}

record LhAnnouncementEnrichmentData(
        String panId,
        String correctionReason,
        ReceptionPlace receptionPlace,
        List<LhScheduleData> schedules,
        List<LhAttachmentData> attachments,
        List<LhSupplyData> supplies
) {
}

record LhScheduleData(
        String sourceIdentifier,
        ScheduleType type,
        String name,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
}

record LhAttachmentData(String sourceIdentifier, String name, AttachmentType type, String url) {
}

record LhComplexData(String name, String expectedMoveInYearMonth) {
}

record LhSupplyData(
        String sourceIdentifier,
        String complexName,
        String housingTypeName,
        YearMonth expectedMoveInMonth,
        Integer totalHouseholdCount,
        Integer supplyHouseholdCount,
        BigDecimal rentalDeposit,
        BigDecimal monthlyRent
) {
}

class LhAnnouncementEnrichmentRejectedException extends RuntimeException {

    private final LhAnnouncementEnrichmentFailureReason reason;

    LhAnnouncementEnrichmentRejectedException(LhAnnouncementEnrichmentFailureReason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    LhAnnouncementEnrichmentFailureReason reason() {
        return reason;
    }
}
