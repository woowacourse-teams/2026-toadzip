package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.announcement.dto.response.SavedAnnouncementPdfExtraction;
import com.toadzip.backend.announcement.service.AnnouncementPdfExtractionService;
import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Document;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Source;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Unit;
import com.toadzip.backend.eligibility.domain.IncomeStandard;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.DraftFromExtraction;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.DraftVersion;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveDraft;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.ExtractionDraft;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.RuleSet;
import com.toadzip.backend.eligibility.exception.EligibilityDraftUnavailableException;
import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;
import com.toadzip.backend.eligibility.repository.EligibilityDraftSourceRepository;
import com.toadzip.backend.eligibility.repository.EligibilityRegionMembership;
import com.toadzip.backend.eligibility.repository.ExtractedEligibilityReader;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.region.repository.RegionSearchRepository;
import com.toadzip.backend.region.repository.RegionSearchResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PDF 보정 화면의 최신 추출 결과(AI 신청자격)와 지금 공급행으로 신청자격을 만들어 곧바로 공개한다.
 * 저장·공개는 관리자 초안 저장·공개(EligibilityRuleSetService)를 그대로 타서 검증·잠금·관계형 투영을 함께 한다.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class EligibilityExtractionDraftService {
    private final AnnouncementPdfExtractionService extractions;
    private final ExtractedEligibilityReader reader;
    private final EligibilityDraftSourceRepository sources;
    private final EligibilityRuleSetService ruleSets;
    private final EligibilityRegionMembership regions;
    private final RegionSearchRepository regionSearch;

    public EligibilityExtractionDraftService(
            AnnouncementPdfExtractionService extractions,
            ExtractedEligibilityReader reader,
            EligibilityDraftSourceRepository sources,
            EligibilityRuleSetService ruleSets,
            EligibilityRegionMembership regions,
            RegionSearchRepository regionSearch) {
        this.extractions = extractions;
        this.reader = reader;
        this.sources = sources;
        this.ruleSets = ruleSets;
        this.regions = regions;
        this.regionSearch = regionSearch;
    }

    /** 저장과 공개를 한 트랜잭션으로 묶는다. 공개가 막히면 초안 저장도 남기지 않는다. */
    @Transactional
    public ExtractionDraft create(long announcementId, DraftFromExtraction request) {
        SavedAnnouncementPdfExtraction latest = latest(announcementId, request.pdfSha256());
        String url = sources.attachmentUrl(announcementId, latest.fileName()).orElseThrow(() ->
                new EligibilityDraftUnavailableException(
                        "추출한 PDF(" + latest.fileName() + ")가 공고 첨부에 없어 원문 주소를 알 수 없습니다. 공고 첨부의 PDF로 추출해 주세요."));
        LocalDate referenceDate = sources.postedDate(announcementId).orElseThrow(() ->
                new EligibilityDraftUnavailableException("모집공고일이 없어 신청자격 기준일을 정할 수 없습니다. 공고 정보를 먼저 채워 주세요."));
        ExtractedEligibilityDraft.Draft draft = ExtractedEligibilityDraft.build(source(latest, url, referenceDate,
                sources.units(announcementId).stream().map(this::normalized).toList()));
        try {
            RuleSet saved = ruleSets.save(announcementId,
                    new SaveDraft(request.expectedDraftId(), request.expectedRevision(), draft.content()));
            // 보정을 적용하면 신청자격도 곧바로 사용자에게 반영한다(사용자 결정). 공고가 아직 공개 전이면 저장만 한다.
            if (!ruleSets.publishable(announcementId)) {
                List<String> notices = new ArrayList<>(draft.notices());
                notices.add("공고가 아직 공개 전이라 신청자격은 저장만 했습니다. 공고를 공개한 뒤 보정을 다시 적용하면 사용자에게 반영됩니다.");
                return new ExtractionDraft(saved, false, List.copyOf(notices));
            }
            RuleSet published = ruleSets.publish(announcementId, new DraftVersion(saved.id(), saved.revision()));
            return new ExtractionDraft(published, true, draft.notices());
        } catch (InvalidEligibilityException invalid) {
            // 작성 규칙을 통과하지 못한 초안은 저장·공개하지 않는다. 관리자가 할 일을 이유와 함께 알린다.
            throw new EligibilityDraftUnavailableException("AI 추출로 만든 초안이 신청자격 작성 규칙을 통과하지 못했습니다("
                    + invalid.getMessage() + "). 공고문을 확인해 PDF를 다시 추출하거나 직접 작성해 주세요.");
        }
    }

    /**
     * 보정 적용 전 신청자격 탭이 보이는 서버 변환 결과. 공급행에 잇지 않고 저장하지도 않는다.
     * 적용 전이라 첨부 주소나 모집공고일이 아직 없을 수 있어, 없으면 비운 채 보인다.
     */
    public EligibilityContent preview(long announcementId, String pdfSha256) {
        SavedAnnouncementPdfExtraction latest = latest(announcementId, pdfSha256);
        return ExtractedEligibilityDraft.preview(source(latest,
                sources.attachmentUrl(announcementId, latest.fileName()).orElse(null),
                sources.postedDate(announcementId).orElse(null), List.of()));
    }

    private SavedAnnouncementPdfExtraction latest(long announcementId, String pdfSha256) {
        SavedAnnouncementPdfExtraction latest = extractions.latest(announcementId);
        if (latest == null) {
            throw new EligibilityDraftUnavailableException("PDF 추출 결과가 없습니다. 공고 PDF를 먼저 추출해 주세요.");
        }
        if (pdfSha256 == null || !pdfSha256.equalsIgnoreCase(latest.pdfSha256())) {
            throw new EligibilityDraftUnavailableException(
                    "화면의 PDF와 최신 추출 결과가 다릅니다. 화면을 새로 고친 뒤 다시 만들어 주세요.");
        }
        return latest;
    }

    private Source source(SavedAnnouncementPdfExtraction latest, String url, LocalDate referenceDate, List<Unit> units) {
        List<RegionSearchResult> catalog = regionSearch.findByKeyword("");
        return new Source(
                referenceDate,
                new Document(latest.fileName(), url, latest.extraction().totalPages(), latest.pdfSha256().toLowerCase()),
                units,
                reader.read(latest.extraction().json()),
                incomeTables(),
                name -> regionCodes(catalog, name));
    }

    /** 저장된 공급유형 코드를 화면 이름(행복주택 등)으로, 지역은 판정이 아는 코드만 남긴다. */
    private Unit normalized(Unit unit) {
        return new Unit(unit.rowId(), unit.complexLabel(), unit.typeLabel(), unit.exclusiveArea(), unit.housingComplexId(),
                unit.housingTypeId(), rentalLabel(unit.rentalType()),
                unit.regionCodes().stream().filter(regions::isKnown).toList(), unit.targets());
    }

    private static String rentalLabel(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return RentalType.fromStoredValue(stored).legacyStoredValue();
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private Map<String, Long> incomeTables() {
        return ruleSets.incomeStandards().stream()
                .collect(Collectors.toMap(table -> table.kind() + ":" + table.baseYear(), IncomeStandard::id));
    }

    /**
     * 공고의 지역 이름을 코드로 바꾼다. 시·도 이름이면 시·도 코드, 시군구 이름이면 그 시군구(구가 나뉜 시는 모든 구) 코드다.
     * 시·도 없이 쓴 구 이름이 여러 시·도에 있으면(중구, 강서구) 어느 곳인지 알 수 없어 빈 목록(직접 확인)이다.
     */
    static List<String> regionCodes(List<RegionSearchResult> catalog, String name) {
        String wanted = name.replaceAll("\\s+", " ").strip();
        Set<String> provinces = new LinkedHashSet<>();
        Set<String> districts = new LinkedHashSet<>();
        for (RegionSearchResult region : catalog) {
            // 지역 목록에는 읍·면·동(10자리)도 있지만 신청자격 지역은 시·도·시군구 단위로만 둔다.
            if (region.regionCode().length() != 5) {
                continue;
            }
            if (wanted.equals(region.provinceName())) {
                provinces.add(region.regionCode().substring(0, 2));
            } else if (wanted.equals(region.displayName()) || region.displayName().startsWith(wanted + " ")
                    || wanted.equals(region.districtName())
                    || (region.districtName() != null && region.districtName().startsWith(wanted + " "))) {
                districts.add(region.regionCode());
            }
        }
        if (!provinces.isEmpty()) {
            return List.copyOf(provinces);
        }
        long provinceCount = districts.stream().map(code -> code.substring(0, 2)).distinct().count();
        return provinceCount == 1 ? List.copyOf(districts) : List.of();
    }
}
