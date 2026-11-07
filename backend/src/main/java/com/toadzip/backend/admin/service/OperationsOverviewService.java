package com.toadzip.backend.admin.service;

import com.toadzip.backend.admin.dto.OperationsOverview;
import com.toadzip.backend.announcement.repository.AnnouncementSearchRepository;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository.ExecutionSummary;
import com.toadzip.backend.user.repository.AdminUserQueryRepository;
import com.toadzip.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OperationsOverviewService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ComplexSummaryQueryRepository complexes;
    private final AnnouncementSearchRepository announcements;
    private final AdminUserQueryRepository registrations;
    private final UserRepository users;
    private final DataPipelineExecutionRepository executions;
    private final Clock clock;

    public OperationsOverviewService(ComplexSummaryQueryRepository complexes,
            AnnouncementSearchRepository announcements, AdminUserQueryRepository registrations, UserRepository users,
            DataPipelineExecutionRepository executions, Clock clock) {
        this.complexes = complexes;
        this.announcements = announcements;
        this.registrations = registrations;
        this.users = users;
        this.executions = executions;
        this.clock = clock;
    }

    public OperationsOverview.Complexes complexes() {
        Instant now = now();
        var counts = complexes.countForAdmin(today(now));
        return new OperationsOverview.Complexes(counts.total(), counts.recruiting(), counts.missingCoordinates(),
                counts.reviewRequired(), Map.of("LH", counts.lh(), "SH", counts.sh(), "GH", counts.gh()), now);
    }

    public OperationsOverview.Announcements announcements() {
        Instant now = now();
        var counts = announcements.countForAdmin(today(now));
        return new OperationsOverview.Announcements(
                Map.of("BEFORE_APPLICATION", counts.upcoming(), "APPLYING", counts.applying(),
                        "CLOSED", counts.closed(), "CONDITIONAL", counts.conditional(),
                        "CANCELLED", counts.cancelled(), "UNKNOWN", counts.unknown()),
                counts.attention(), Map.of("SCHEDULE_MISSING", counts.scheduleMissing(),
                        "SUPPLY_MISSING", counts.supplyMissing(), "UNCONFIRMED", counts.unconfirmed()), now);
    }

    public OperationsOverview.Users users(int days) {
        if (days != 7 && days != 30) {
            throw new IllegalArgumentException("기간은 7일 또는 30일이어야 합니다.");
        }
        Instant now = now();
        LocalDate today = today(now);
        LocalDate start = today.minusDays(days - 1L);
        var daily = registrations.countRegistrationsByDay(start, today, LocalDateTime.ofInstant(now, SEOUL));
        long previous = registrations.countRegisteredBetween(
                start.minusDays(days).atStartOfDay(), start.atStartOfDay());
        var trend = daily.stream().map(day -> new OperationsOverview.DayCount(day.day(), day.count())).toList();
        long newUsers = daily.stream().mapToLong(AdminUserQueryRepository.DailyRegistrations::count).sum();
        return new OperationsOverview.Users(users.count(), newUsers, previous, trend, now);
    }

    public OperationsOverview.Collections collections() {
        Instant now = now();
        return new OperationsOverview.Collections(List.of(
                collection("LH", List.of(DataPipelineType.ANNOUNCEMENT_COLLECTION, DataPipelineType.ANNOUNCEMENT_SYNC,
                        DataPipelineType.ANNOUNCEMENT_REGISTRATION)),
                collection("SH", List.of(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION)),
                collection("GH", List.of(DataPipelineType.GH_ANNOUNCEMENT_COLLECTION))), now);
    }

    private OperationsOverview.Collection collection(String agency, List<DataPipelineType> types) {
        var latest = executions.findFirstByTypeInOrderByStartedAtDescIdDesc(types);
        var successful = executions.findFirstByTypeInAndStatusAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(
                types, DataPipelineExecutionStatus.COMPLETED);
        return new OperationsOverview.Collection(agency, successful.map(ExecutionSummary::getFinishedAt).orElse(null),
                latest.map(ExecutionSummary::getStartedAt).orElse(null),
                latest.map(execution -> execution.getStatus().name()).orElse(null));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private LocalDate today(Instant instant) {
        return instant.atZone(SEOUL).toLocalDate();
    }
}
