package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.AdminAnnouncementImport;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRegistrationRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRegistrationRequest.ComplexSelectionRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.AttachmentRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.ScheduleRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.SupplyRowRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.SupplyTargetRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementImportCreateResponse;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementImportValidationResponse;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementImportValidationResponse.SupplyRowMatchResponse;
import com.toadzip.backend.announcement.exception.AnnouncementImportDuplicatedException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementImportException;
import com.toadzip.backend.announcement.repository.AdminAnnouncementImportRepository;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAnnouncementImportRegistrationService {

    private final AdminAnnouncementImportValidationService validationService;
    private final AdminAnnouncementImportJson importJson;
    private final AdminAnnouncementImportRepository importRepository;
    private final AnnouncementRepository announcementRepository;
    private final SupplyRowRepository supplyRowRepository;
    private final SupplyTargetRepository supplyTargetRepository;
    private final AnnouncementScheduleRepository scheduleRepository;
    private final AnnouncementAttachmentRepository attachmentRepository;
    private final HousingComplexRepository housingComplexRepository;
    private final AdminAnnouncementSourceIdentifierGenerator identifierGenerator;

    public AdminAnnouncementImportRegistrationService(
            AdminAnnouncementImportValidationService validationService,
            AdminAnnouncementImportJson importJson,
            AdminAnnouncementImportRepository importRepository,
            AnnouncementRepository announcementRepository,
            SupplyRowRepository supplyRowRepository,
            SupplyTargetRepository supplyTargetRepository,
            AnnouncementScheduleRepository scheduleRepository,
            AnnouncementAttachmentRepository attachmentRepository,
            HousingComplexRepository housingComplexRepository,
            AdminAnnouncementSourceIdentifierGenerator identifierGenerator
    ) {
        this.validationService = validationService;
        this.importJson = importJson;
        this.importRepository = importRepository;
        this.announcementRepository = announcementRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.supplyTargetRepository = supplyTargetRepository;
        this.scheduleRepository = scheduleRepository;
        this.attachmentRepository = attachmentRepository;
        this.housingComplexRepository = housingComplexRepository;
        this.identifierGenerator = identifierGenerator;
    }

    @Transactional
    public AdminAnnouncementImportCreateResponse register(
            AdminAnnouncementImportRegistrationRequest registrationRequest,
            String registeredBy
    ) {
        AdminAnnouncementImportRequest request = registrationRequest.importData();
        AdminAnnouncementImportValidationResponse validation = validationService.validate(request);
        ensureRegisterable(validation);
        Map<Integer, HousingComplex> selectedComplexes = resolveSelections(registrationRequest, validation);
        String originalJson = importJson.serialize(request);
        ensureJsonSize(originalJson);

        Announcement announcement = announcementRepository.save(createAnnouncement(request));
        List<SupplyRow> supplyRows = saveSupplyRows(request, announcement, selectedComplexes);
        int supplyTargetCount = saveSupplyTargets(request, supplyRows);
        scheduleRepository.saveAll(createSchedules(request, announcement));
        attachmentRepository.saveAll(createAttachments(request, announcement));
        AdminAnnouncementImport history = saveHistory(
                request,
                validation.jsonHash(),
                originalJson,
                registeredBy,
                announcement
        );
        return new AdminAnnouncementImportCreateResponse(
                history.getId(),
                announcement.getId(),
                supplyRows.size(),
                request.schedules().size(),
                request.attachments().size(),
                supplyTargetCount
        );
    }

    private void ensureRegisterable(AdminAnnouncementImportValidationResponse validation) {
        if (validation.duplicated()) {
            throw new AnnouncementImportDuplicatedException();
        }
        if (!validation.registerable()) {
            throw new InvalidAnnouncementImportException();
        }
    }

    private Map<Integer, HousingComplex> resolveSelections(
            AdminAnnouncementImportRegistrationRequest request,
            AdminAnnouncementImportValidationResponse validation
    ) {
        Map<Integer, Long> selectedIds = selectionIds(request.complexSelections());
        if (selectedIds.size() != validation.supplyRows().size()) {
            throw new InvalidAnnouncementImportException();
        }
        Map<Integer, HousingComplex> result = new HashMap<>();
        for (SupplyRowMatchResponse match : validation.supplyRows()) {
            long selectedId = selectedIdFor(match, selectedIds);
            HousingComplex complex = housingComplexRepository.findById(selectedId)
                    .orElseThrow(InvalidAnnouncementImportException::new);
            result.put(match.supplyRowIndex(), complex);
        }
        return Map.copyOf(result);
    }

    private Map<Integer, Long> selectionIds(List<ComplexSelectionRequest> selections) {
        Map<Integer, Long> selectedIds = new HashMap<>();
        for (ComplexSelectionRequest selection : selections) {
            if (selectedIds.put(selection.supplyRowIndex(), selection.housingComplexId()) != null) {
                throw new InvalidAnnouncementImportException();
            }
        }
        return selectedIds;
    }

    private long selectedIdFor(SupplyRowMatchResponse match, Map<Integer, Long> selectedIds) {
        Long selectedId = selectedIds.get(match.supplyRowIndex());
        if (selectedId == null) {
            throw new InvalidAnnouncementImportException();
        }
        boolean candidate = match.candidates().stream()
                .anyMatch(value -> value.housingComplexId() == selectedId);
        if (!candidate) {
            throw new InvalidAnnouncementImportException();
        }
        return selectedId;
    }

    private Announcement createAnnouncement(AdminAnnouncementImportRequest request) {
        return Announcement.create(
                identifierGenerator.generateAnnouncementIdentifier(),
                null,
                null,
                request.announcement().name(),
                AnnouncementPublicationType.ORIGINAL,
                request.announcement().rentalType(),
                request.announcement().recruitmentType(),
                request.announcement().agencyCode(),
                request.announcement().postedDate(),
                request.announcement().applicationStartDate(),
                request.announcement().applicationEndDate(),
                request.announcement().winnerAnnouncementDate(),
                request.source().originalUrl(),
                null,
                0L,
                ReceptionPlace.create(
                        request.receptionPlace().name(),
                        request.receptionPlace().method(),
                        request.receptionPlace().address(),
                        request.receptionPlace().contact(),
                        request.receptionPlace().url()
                )
        );
    }

    private List<SupplyRow> saveSupplyRows(
            AdminAnnouncementImportRequest request,
            Announcement announcement,
            Map<Integer, HousingComplex> selectedComplexes
    ) {
        List<SupplyRow> rows = new ArrayList<>();
        for (int index = 0; index < request.supplyRows().size(); index++) {
            SupplyRowRequest row = request.supplyRows().get(index);
            rows.add(SupplyRow.create(
                    announcement,
                    selectedComplexes.get(index),
                    null,
                    identifierGenerator.generateSupplyRowIdentifier(),
                    index,
                    row.complexReference().sourceComplexName(),
                    row.sourceHousingTypeName(),
                    row.complexReference().pnu(),
                    row.expectedMoveInMonth(),
                    row.supplyCategory(),
                    null,
                    row.totalSupplyHouseholdCount()
            ));
        }
        return supplyRowRepository.saveAll(rows);
    }

    private int saveSupplyTargets(AdminAnnouncementImportRequest request, List<SupplyRow> supplyRows) {
        List<SupplyTarget> targets = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < request.supplyRows().size(); rowIndex++) {
            List<SupplyTargetRequest> sourceTargets = request.supplyRows().get(rowIndex).targets();
            for (int targetIndex = 0; targetIndex < sourceTargets.size(); targetIndex++) {
                SupplyTargetRequest target = sourceTargets.get(targetIndex);
                targets.add(SupplyTarget.create(
                        supplyRows.get(rowIndex),
                        target.target(),
                        target.supplyRank(),
                        target.supplyHouseholdCount(),
                        target.reserveCount(),
                        target.rentalDeposit(),
                        target.monthlyRent(),
                        target.convertedDeposit(),
                        target.applicationCondition(),
                        targetIndex
                ));
            }
        }
        supplyTargetRepository.saveAll(targets);
        return targets.size();
    }

    private List<AnnouncementSchedule> createSchedules(
            AdminAnnouncementImportRequest request,
            Announcement announcement
    ) {
        List<AnnouncementSchedule> schedules = new ArrayList<>();
        for (int index = 0; index < request.schedules().size(); index++) {
            ScheduleRequest schedule = request.schedules().get(index);
            schedules.add(AnnouncementSchedule.create(
                    announcement,
                    schedule.scheduleType(),
                    schedule.name(),
                    schedule.startAt(),
                    schedule.endAt(),
                    index
            ));
        }
        return List.copyOf(schedules);
    }

    private List<AnnouncementAttachment> createAttachments(
            AdminAnnouncementImportRequest request,
            Announcement announcement
    ) {
        List<AnnouncementAttachment> attachments = new ArrayList<>();
        for (int index = 0; index < request.attachments().size(); index++) {
            AttachmentRequest attachment = request.attachments().get(index);
            attachments.add(AnnouncementAttachment.create(
                    announcement,
                    attachment.fileName(),
                    attachment.fileType(),
                    attachment.fileUrl(),
                    index
            ));
        }
        return List.copyOf(attachments);
    }

    private AdminAnnouncementImport saveHistory(
            AdminAnnouncementImportRequest request,
            String jsonHash,
            String originalJson,
            String registeredBy,
            Announcement announcement
    ) {
        AdminAnnouncementImport history = AdminAnnouncementImport.create(
                request.schemaVersion(),
                request.source().originalUrl(),
                request.source().sourceDocumentId(),
                jsonHash,
                originalJson,
                registeredBy,
                OffsetDateTime.now(ZoneOffset.UTC),
                announcement
        );
        try {
            return importRepository.saveAndFlush(history);
        } catch (DataIntegrityViolationException exception) {
            throw new AnnouncementImportDuplicatedException();
        }
    }

    private void ensureJsonSize(String originalJson) {
        if (!importJson.isWithinSizeLimit(originalJson)) {
            throw new InvalidAnnouncementImportException();
        }
    }
}
