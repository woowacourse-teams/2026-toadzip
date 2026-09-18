package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LhAnnouncementEnrichmentMapper {

    private static final int MAX_RECEPTION_NAME_LENGTH = 255;

    private final LhAnnouncementValueParser parser = new LhAnnouncementValueParser();
    private final LhAttachmentTypePolicy attachmentTypePolicy = new LhAttachmentTypePolicy();

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
            return parser.yearMonth(matches.getFirst().expectedMoveInYearMonth(), "입주예정월");
        }
        if (matches.isEmpty()
                && complexes.size() == 1
                && !SupplyNameNormalizer.complexName(supply.getComplexLabel()).isEmpty()
                && !SupplyNameNormalizer.complexName(complexes.getFirst().name()).isEmpty()) {
            return parser.yearMonth(complexes.getFirst().expectedMoveInYearMonth(), "입주예정월");
        }
        return null;
    }

    private boolean sameComplex(String left, String right) {
        return SupplyNameNormalizer.sameComplex(left, right);
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
        addDate(
                schedules,
                panId,
                source,
                ScheduleType.WINNER_ANNOUNCEMENT,
                "당첨자 발표",
                source.getDocumentTargetAnnouncementDate()
        );
        addRange(
                schedules, panId, source, ScheduleType.DOCUMENT_SUBMISSION, "서류제출",
                source.getDocumentSubmissionBeginDate(), source.getDocumentSubmissionEndDate()
        );
        addRange(
                schedules,
                panId,
                source,
                ScheduleType.CONTRACT,
                "계약",
                source.getContractBeginDate(),
                source.getContractEndDate()
        );
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
        if (parser.unavailable(range)) {
            return;
        }
        List<LocalDateTime> values = parser.dateTimes(range, name);
        if (values.size() < 2) {
            throw parser.invalid(name + " 기간의 시작·종료 시각을 해석할 수 없습니다.");
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
        if (parser.unavailable(begin) && parser.unavailable(end)) {
            return;
        }
        if (parser.unavailable(begin) || parser.unavailable(end)) {
            throw parser.invalid(name + " 기간의 시작 또는 종료 시각이 없습니다.");
        }
        schedules.add(schedule(
                panId,
                source,
                type,
                name,
                parser.dateTime(begin, name),
                parser.dateTime(end, name)
        ));
    }

    private void addDate(
            List<LhScheduleData> schedules,
            String panId,
            LhAnnouncementDetailSource source,
            ScheduleType type,
            String name,
            String value
    ) {
        if (!parser.unavailable(value)) {
            LocalDateTime at = parser.dateTime(value, name);
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
            throw parser.invalid(name + " 종료 시각이 시작 시각보다 빠릅니다.");
        }
        return new LhScheduleData(
                identifier(panId, "SCHEDULE", source.getSourceOrder(), type.name()), type, name, startAt, endAt
        );
    }

    private LhAttachmentData attachmentOf(String panId, LhAnnouncementDetailSource source) {
        if (parser.blank(source.getName()) || parser.blank(source.getUrl())) {
            throw parser.invalid("첨부파일명 또는 URL이 없습니다.");
        }
        return new LhAttachmentData(
                identifier(panId, "ANNOUNCEMENT_FILE", source.getSourceOrder(), null), source.getName(),
                attachmentTypePolicy.classify(source.getKind()), source.getUrl()
        );
    }

    private LhSupplyData supplyOf(String panId, LhAnnouncementSupplySource source, YearMonth expectedMoveInMonth) {
        if (parser.blank(source.getComplexLabel()) || parser.blank(source.getTypeName())) {
            throw parser.invalid("LH 공급 원본의 단지명 또는 주택형명이 없습니다.");
        }
        return new LhSupplyData(
                identifier(panId, "SUPPLY", source.getSourceOrder(), null), source.getComplexLabel(),
                source.getTypeName(),
                expectedMoveInMonth,
                parser.integer(source.getTotalUnitCount(), "전체 세대수"),
                parser.integer(source.getSuppliedUnitCount(), "공급 세대수"),
                parser.amount(source.getDepositText(), "임대보증금"),
                parser.amount(source.getMonthlyRentText(), "월 임대료")
        );
    }

    private String identifier(String panId, String datasetType, Integer sourceOrder, String suffix) {
        String identifier = "LH:" + panId + ":" + datasetType + ":" + sourceOrder;
        if (suffix == null) {
            return identifier;
        }
        return identifier + ":" + suffix;
    }

    private String joined(String first, String second) {
        if (parser.blank(first)) {
            return second;
        }
        if (parser.blank(second)) {
            return first;
        }
        return first + " " + second;
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
