package com.toadzip.backend.ingest.lh.refinement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.AnnouncementNameClassification;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.LhAnnouncementValues;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.detail.domain.LhAnnouncementDetailSourceView;
import com.toadzip.backend.ingest.lh.collection.detail.repository.LhAnnouncementDetailSourceReader;
import com.toadzip.backend.ingest.lh.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.lh.collection.supply.repository.LhAnnouncementSupplySourceReader;
import com.toadzip.backend.ingest.lh.refinement.domain.LhRentalTypes;
import com.toadzip.backend.ingest.lh.refinement.domain.LhRevisionName;
import com.toadzip.backend.ingest.lh.refinement.dto.LhAnnouncementRefinementReport;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementComplexLinker.DetailComplex;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementEnrichmentMapper.LhScheduleData;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementEnrichmentMapper;
import com.toadzip.backend.ingest.lh.refinement.service.LhScheduleAttachmentWriter;
import com.toadzip.backend.ingest.lh.refinement.service.LhSupplyRowWriter.SupplyLine;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LH 공고목록·공급·상세로 공고 하나를 정제한다. 같은 LH 공고 ID의 휴지통이 아닌 가장 작은 ID 공고를 이어받고, 없으면 `LH:{공고 ID}`로 만든다.
 * 관리자가 고친 공고 값과 확정한 접수 기간은 덮지 않는다. 단지·주택형은 만들거나 바꾸지 않는다. 공고일이 7일 넘게 지난 새 공고는 알리지 않는다.
 */
@Component
@RequiredArgsConstructor
class LhAnnouncementRefiner {

    private static final String COMPLEX_DATASET = "COMPLEX";
    private static final int LATE_ARRIVAL_DAYS = 7;

    private final AnnouncementRepository announcements;
    private final LhAnnouncementSupplySourceReader supplySources;
    private final LhAnnouncementDetailSourceReader detailSources;
    private final LhAnnouncementEnrichmentMapper mapper;
    private final LhScheduleAttachmentWriter scheduleWriter;
    private final LhAnnouncementComplexLinker complexLinker;
    private final LhSupplyRowWriter supplyRowWriter;
    private final NotificationOutboxRepository notificationOutbox;
    private final Clock clock;
    private final EntityManager entityManager;

    Outcome refine(LhAnnouncementCatalogSourceView catalog, LhComplexIndex index, Tally tally) {
        LhAnnouncementRequest request = LhAnnouncementRequest.from(catalog);
        String requestHash = request.requestHash();
        List<LhAnnouncementDetailSourceView> details = detailSources.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                request.panId(), requestHash);
        if (details.isEmpty()) {
            return Outcome.SOURCES_MISSING;
        }
        List<LhAnnouncementSupplySourceView> supplies = supplySources.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                request.panId(), requestHash);
        Optional<RentalType> supplyType = LhRentalTypes.productType(catalog.getAnnouncementTypeCode(),
                catalog.getSupplyInfoTypeCode());
        if (supplyType.isEmpty()) {
            return Outcome.SUPPLY_TYPE_UNKNOWN;
        }
        LhAnnouncementEnrichmentData data = mapper.map(request.panId(), details, supplies);
        List<Announcement> samePan = announcements.findAllByLhPanIdOrderByIdAsc(request.panId());
        // 관리자 PDF 보정과 같은 공고를 동시에 쓰지 않도록 이어받을 공고를 잠그고 그 상태로 다시 판단한다.
        Optional<Announcement> existing = samePan.stream().filter(found -> !found.isAdminDeleted()).findFirst()
                .map(this::lockedLatest)
                .filter(found -> !found.isAdminDeleted());
        if (existing.isEmpty() && !samePan.isEmpty()) {
            return Outcome.TRASHED;
        }
        Optional<LhAnnouncementValues> values = values(catalog, data, supplyType.get(), existing);
        if (values.isEmpty()) {
            return Outcome.VALUES_MISSING;
        }
        if (existing.isEmpty() && values.get().winnerAnnouncementDate() == null) {
            return Outcome.WINNER_DATE_MISSING;
        }
        Announcement announcement = existing.map(found -> adopt(found, values.get(), tally))
                .orElseGet(() -> create(request.panId(), values.get(), tally));
        announcement.enrichFromLh(request.panId(), data.correctionReason(), data.receptionPlace());
        scheduleWriter.write(announcement, data, null);
        linkRevision(announcement, tally);
        Map<String, AnnouncementComplex> complexes = complexLinker.observe(announcement, detailComplexes(details),
                supplies, supplyType.get(), index, tally.complexes);
        writeSupplies(announcement, supplies, data, complexes, tally);
        if (announcement.observeLhSupply(fingerprint(List.copyOf(complexes.keySet()), supplies))) {
            tally.recheckRequired++;
        }
        if (existing.isPresent()) {
            return Outcome.ADOPTED;
        }
        return Outcome.CREATED;
    }

    private Announcement create(String panId, LhAnnouncementValues values, Tally tally) {
        Announcement announcement = announcements.save(Announcement.createFromLh(panId, values));
        // 정정·취소공고는 원공고를 찾지 못해도 새 공고로 알리지 않는다.
        if (!new LhRevisionName(values.name()).isRevision()
                && !values.postedDate().isBefore(LocalDate.now(clock).minusDays(LATE_ARRIVAL_DAYS))) {
            notificationOutbox.appendNewAnnouncement(announcement);
        }
        tally.created++;
        return announcement;
    }

    private Announcement adopt(Announcement announcement, LhAnnouncementValues values, Tally tally) {
        if (announcement.updateFromLh(values)) {
            tally.updated++;
        }
        return announcement;
    }

    private void writeSupplies(Announcement announcement, List<LhAnnouncementSupplySourceView> supplies,
            LhAnnouncementEnrichmentData data, Map<String, AnnouncementComplex> complexes, Tally tally) {
        if (announcement.suppliedByLh()) {
            supplyRowWriter.writeLhOwned(announcement, lines(supplies, data), complexes,
                    AnnouncementNameClassification.supplyCategory(announcement.getName()), tally.supplies);
            return;
        }
        supplyRowWriter.fillDocumentOwned(announcement, complexes, tally.supplies);
        tally.documentOwned++;
    }

    /** 앞서 읽은 엔티티는 잠금 조회가 상태를 새로 읽지 않으므로 잠그면서 DB 값으로 다시 읽는다. */
    private Announcement lockedLatest(Announcement announcement) {
        entityManager.refresh(announcement, LockModeType.PESSIMISTIC_WRITE);
        return announcement;
    }

    /** 원천에 없는 값은 이미 있는 공고 값을 쓴다. 새 공고의 당첨자 발표일이 없으면 비워 두고 만들 때 보류한다. */
    private Optional<LhAnnouncementValues> values(LhAnnouncementCatalogSourceView catalog, LhAnnouncementEnrichmentData data,
            RentalType supplyType, Optional<Announcement> existing) {
        Optional<String> name = Optional.ofNullable(catalog.getAnnouncementName()).filter(text -> !text.isBlank())
                .or(() -> existing.map(Announcement::getName));
        Optional<LocalDate> posted = catalog.noticeLocalDate().or(() -> existing.map(Announcement::getPostedDate));
        Optional<String> url = Optional.ofNullable(catalog.getDetailUrl()).filter(text -> !text.isBlank())
                .or(() -> existing.map(Announcement::getOriginalUrl));
        Optional<LocalDate> start = applicationDate(data, LhScheduleData::startAt, Comparator.naturalOrder())
                .or(() -> existing.map(Announcement::getApplicationStartDate));
        Optional<LocalDate> end = applicationDate(data, this::endOf, Comparator.reverseOrder())
                .or(catalog::closingLocalDate).or(() -> existing.map(Announcement::getApplicationEndDate));
        if (name.isEmpty() || posted.isEmpty() || url.isEmpty() || start.isEmpty() || end.isEmpty()
                || end.get().isBefore(start.get())) {
            return Optional.empty();
        }
        return Optional.of(new LhAnnouncementValues(name.get(), supplyType,
                AnnouncementNameClassification.recruitmentType(name.get()), posted.get(), start.get(), end.get(),
                winnerDate(data).orElse(null), url.get()));
    }

    private Optional<LocalDate> applicationDate(LhAnnouncementEnrichmentData data,
            Function<LhScheduleData, LocalDateTime> date, Comparator<LocalDate> order) {
        return data.schedules().stream().filter(schedule -> schedule.type() == ScheduleType.APPLICATION)
                .map(date).filter(Objects::nonNull).map(LocalDateTime::toLocalDate).min(order);
    }

    private LocalDateTime endOf(LhScheduleData schedule) {
        return Objects.requireNonNullElse(schedule.endAt(), schedule.startAt());
    }

    private Optional<LocalDate> winnerDate(LhAnnouncementEnrichmentData data) {
        return data.schedules().stream().filter(schedule -> schedule.type() == ScheduleType.WINNER_ANNOUNCEMENT)
                .map(LhScheduleData::startAt).filter(Objects::nonNull).map(LocalDateTime::toLocalDate)
                .min(Comparator.naturalOrder());
    }

    /**
     * LH 공고목록에는 정정·취소공고와 원공고를 잇는 값이 없다. 공고명 머리말(`[정정공고]`, `[취소공고]`)을 뗀 이름이 같고 공고일이 늦지 않은
     * 이전 LH 공고 중 가장 최근 공고가 하나일 때 잇는다. 이미 다른 공고가 이은 공고는 고르지 않는다.
     */
    private void linkRevision(Announcement revision, Tally tally) {
        LhRevisionName name = new LhRevisionName(revision.getName());
        Optional<AnnouncementPublicationType> type = name.publicationType();
        if (type.isEmpty() || revision.getPreviousAnnouncement() != null) {
            return;
        }
        String base = name.baseName();
        List<Announcement> candidates = announcements
                .findLhLinkableOriginals(revision.getId(), revision.getPostedDate()).stream()
                .filter(candidate -> new LhRevisionName(candidate.getName()).baseName().equals(base))
                .toList();
        Optional<LocalDate> latest = candidates.stream().map(Announcement::getPostedDate).max(Comparator.naturalOrder());
        List<Announcement> newest = candidates.stream()
                .filter(candidate -> latest.filter(candidate.getPostedDate()::equals).isPresent()).toList();
        if (newest.size() != 1) {
            tally.unlinkedRevisionPanIds.add(revision.getLhPanId());
            return;
        }
        if (revision.linkRevision(newest.getFirst(), type.get())) {
            tally.revisionsLinked++;
            tally.linkedRevisionPanIds.add(revision.getLhPanId());
        }
    }

    /** 재확인 기준 지문: 공고 단지 순서와 각 행의 주택형명·대표 면적·공급면적·모집수·보증금·임대료 원문. */
    private String fingerprint(List<String> complexNames, List<LhAnnouncementSupplySourceView> supplies) {
        List<Object> parts = new ArrayList<>(complexNames);
        for (LhAnnouncementSupplySourceView row : supplies) {
            parts.add(String.join("\u001f", Objects.toString(row.getComplexLabel(), ""),
                    Objects.toString(row.getTypeName(), ""), Objects.toString(row.getExclusiveArea(), ""),
                    Objects.toString(row.getSupplyArea(), ""), Objects.toString(row.getSuppliedUnitCount(), ""),
                    Objects.toString(row.getDepositText(), ""), Objects.toString(row.getMonthlyRentText(), "")));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\u001e", parts.stream().map(String::valueOf).toList())
                            .getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 같은 단지의 상세 행 중 주소·세대수가 있는 첫 값을 쓴다. 상세 행의 일부만 주소를 가진다. */
    private List<DetailComplex> detailComplexes(List<LhAnnouncementDetailSourceView> details) {
        Map<String, DetailComplex> complexes = new LinkedHashMap<>();
        for (LhAnnouncementDetailSourceView row : details) {
            if (!COMPLEX_DATASET.equals(row.getDatasetType()) || row.getComplexName() == null
                    || row.getComplexName().isBlank()) {
                continue;
            }
            Integer total = count(row.getTotalUnitCount());
            complexes.merge(row.getComplexName(), new DetailComplex(row.getComplexName(), row.getAddress(), total),
                    (first, second) -> new DetailComplex(first.name(),
                            Objects.requireNonNullElse(first.roadAddress(), second.roadAddress()),
                            Objects.requireNonNullElse(first.totalHouseholdCount(), second.totalHouseholdCount())));
        }
        return List.copyOf(complexes.values());
    }

    private static Integer count(String value) {
        if (value == null || !value.strip().matches("[0-9]{1,9}")) {
            return null;
        }
        return Integer.valueOf(value.strip());
    }

    private List<SupplyLine> lines(List<LhAnnouncementSupplySourceView> supplies, LhAnnouncementEnrichmentData data) {
        List<SupplyLine> lines = new ArrayList<>();
        for (int index = 0; index < supplies.size(); index++) {
            LhSupplyData supply = data.supplies().get(index);
            lines.add(new SupplyLine(supplies.get(index), supply));
        }
        return lines;
    }

    enum Outcome {
        CREATED,
        ADOPTED,
        SOURCES_MISSING,
        SUPPLY_TYPE_UNKNOWN,
        VALUES_MISSING,
        WINNER_DATE_MISSING,
        TRASHED
    }

    static final class Tally {

        void add(Tally other) {
            created += other.created;
            adopted += other.adopted;
            updated += other.updated;
            other.held.forEach((reason, count) -> held.merge(reason, count, Integer::sum));
            documentOwned += other.documentOwned;
            recheckRequired += other.recheckRequired;
            revisionsLinked += other.revisionsLinked;
            linkedRevisionPanIds.addAll(other.linkedRevisionPanIds);
            unlinkedRevisionPanIds.removeAll(other.linkedRevisionPanIds);
            unlinkedRevisionPanIds.addAll(other.unlinkedRevisionPanIds);
            complexes.add(other.complexes);
            supplies.add(other.supplies);
        }

        /** 만든 공고는 정제하면서 세므로 여기서는 이어받은 공고와 보류 이유만 센다. */
        void count(Outcome outcome) {
            switch (outcome) {
                case CREATED -> { }
                case ADOPTED -> adopted++;
                default -> held.merge(outcome.name(), 1, Integer::sum);
            }
        }

        Set<String> unlinkedRevisionPanIds() {
            return Set.copyOf(unlinkedRevisionPanIds);
        }

        /** 실행 끝 재연결의 결과를 합친다. 처음 정제에서 센 공고(만든·이어받은·보류)는 다시 세지 않는다. */
        void addRelinked(Tally other, Set<String> retriedPanIds) {
            updated += other.updated;
            recheckRequired += other.recheckRequired;
            revisionsLinked += other.revisionsLinked;
            unlinkedRevisionPanIds.removeAll(retriedPanIds);
            unlinkedRevisionPanIds.addAll(other.unlinkedRevisionPanIds);
            complexes.add(other.complexes);
            supplies.add(other.supplies);
        }

        LhAnnouncementRefinementReport toReport(int targetCount, int failedCount) {
            return new LhAnnouncementRefinementReport(targetCount, created, adopted, updated, held, failedCount,
                    documentOwned, recheckRequired, revisionsLinked, unlinkedRevisionPanIds.size(), complexes.toCounts(),
                    supplies.toCounts());
        }

        private int created;
        private int adopted;
        private int updated;
        private final Map<String, Integer> held = new TreeMap<>();
        private int documentOwned;
        private int recheckRequired;
        private int revisionsLinked;
        private final Set<String> linkedRevisionPanIds = new LinkedHashSet<>();
        /** 원공고를 하나로 정하지 못한 정정·취소공고의 LH 공고 ID. 실행 끝에서 다시 잇는다. */
        private final Set<String> unlinkedRevisionPanIds = new LinkedHashSet<>();
        private final LhAnnouncementComplexLinker.Tally complexes = new LhAnnouncementComplexLinker.Tally();
        private final LhSupplyRowWriter.Tally supplies = new LhSupplyRowWriter.Tally();
    }
}
