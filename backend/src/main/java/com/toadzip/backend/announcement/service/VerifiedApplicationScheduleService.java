package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.dto.request.VerifiedApplicationSchedulesRequest;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.repository.AnnouncementApplicationScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifiedApplicationScheduleService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementApplicationScheduleRepository scheduleRepository;
    private final SupplyRowRepository supplyRowRepository;
    private final com.toadzip.backend.admin.repository.AdminDataChangeRepository changes;
    private final tools.jackson.databind.ObjectMapper json;

    public VerifiedApplicationScheduleService(AnnouncementRepository announcementRepository,
            AnnouncementApplicationScheduleRepository scheduleRepository, SupplyRowRepository supplyRowRepository,
            com.toadzip.backend.admin.repository.AdminDataChangeRepository changes,
            tools.jackson.databind.ObjectMapper json) {
        this.announcementRepository = announcementRepository;
        this.scheduleRepository = scheduleRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.changes = changes;
        this.json = json;
    }

    @Transactional
    public void replace(long announcementId, VerifiedApplicationSchedulesRequest request) {
        replace(announcementId, request, null, "system");
    }

    @Transactional
    public void replace(long announcementId, VerifiedApplicationSchedulesRequest request, Long version, String actor) {
        Announcement announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(AnnouncementNotFoundException::new);
        if (announcement.isAdminDeleted()) { throw new InvalidAnnouncementRequestException(); }
        if (version != null && version != announcement.getVersion()) {
            throw new com.toadzip.backend.admin.exception.AdminDataConflictException("공고가 변경되었습니다. 새로 조회해 주세요.");
        }
        var previous = scheduleRepository.findAllByAnnouncementIdIn(List.of(announcementId)).stream()
                .map(value -> new VerifiedApplicationSchedulesRequest.Schedule(complexId(value), value.getSupplyRank(),
                        value.getState(), value.getCondition(), value.getStartDate(), value.getEndDate(),
                        value.getStartTime(), value.getEndTime(), value.getSourceUrl(), value.getSourcePage())).toList();
        Map<Long, HousingComplex> complexes = supplyRowRepository.findAllByAnnouncementIdIn(List.of(announcementId))
                .stream().map(row -> row.getHousingComplex()).filter(complex -> complex != null)
                .collect(Collectors.toMap(HousingComplex::getId, Function.identity(), (left, right) -> left));
        List<AnnouncementApplicationSchedule> schedules = new ArrayList<>();
        for (var source : request.schedules()) {
            HousingComplex complex = null;
            if (source.housingComplexId() != null) {
                complex = complexes.get(source.housingComplexId());
                if (complex == null) {
                    throw new InvalidAnnouncementRequestException();
                }
            }
            schedules.add(schedule(announcement, complex, source));
        }
        LocalDate start = schedules.stream().map(AnnouncementApplicationSchedule::getStartDate)
                .min(LocalDate::compareTo).orElseThrow(InvalidAnnouncementRequestException::new);
        LocalDate end = schedules.stream().map(AnnouncementApplicationSchedule::getEndDate)
                .max(LocalDate::compareTo).orElseThrow(InvalidAnnouncementRequestException::new);
        scheduleRepository.deleteAll(scheduleRepository.findAllByAnnouncementIdIn(List.of(announcementId)));
        scheduleRepository.saveAll(schedules);
        announcement.confirmApplicationPeriod(start, end);
        announcement.recordAdminSupplyChange();
        changes.save(new com.toadzip.backend.admin.domain.AdminDataChange("ANNOUNCEMENT", announcementId,
                "UPDATE_SCHEDULE", actor, json.writeValueAsString(previous), json.writeValueAsString(request.schedules())));
    }

    private Long complexId(AnnouncementApplicationSchedule schedule) {
        if (schedule.getHousingComplex() == null) { return null; }
        return schedule.getHousingComplex().getId();
    }

    private AnnouncementApplicationSchedule schedule(Announcement announcement, HousingComplex complex,
            VerifiedApplicationSchedulesRequest.Schedule source) {
        try {
            return AnnouncementApplicationSchedule.verified(announcement, complex, source.supplyRank(),
                    source.state(), source.condition(), source.startDate(), source.endDate(),
                    source.startTime(), source.endTime(), source.sourceUrl(), source.sourcePage());
        }
        catch (IllegalArgumentException exception) {
            throw new InvalidAnnouncementRequestException();
        }
    }
}
