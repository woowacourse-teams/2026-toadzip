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

    public VerifiedApplicationScheduleService(AnnouncementRepository announcementRepository,
            AnnouncementApplicationScheduleRepository scheduleRepository, SupplyRowRepository supplyRowRepository) {
        this.announcementRepository = announcementRepository;
        this.scheduleRepository = scheduleRepository;
        this.supplyRowRepository = supplyRowRepository;
    }

    @Transactional
    public void replace(long announcementId, VerifiedApplicationSchedulesRequest request) {
        Announcement announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(AnnouncementNotFoundException::new);
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
