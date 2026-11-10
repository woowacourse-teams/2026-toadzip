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
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveDraft;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.RuleSet;
import com.toadzip.backend.eligibility.exception.EligibilityDraftUnavailableException;
import com.toadzip.backend.eligibility.repository.EligibilityDraftSourceRepository;
import com.toadzip.backend.eligibility.repository.EligibilityRegionMembership;
import com.toadzip.backend.eligibility.repository.ExtractedEligibilityReader;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.region.repository.RegionSearchRepository;
import com.toadzip.backend.region.repository.RegionSearchResult;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * PDF 보정 화면의 최신 추출 결과(AI 신청자격)와 지금 공급행으로 검토 전 신청자격 초안을 만든다.
 * 저장은 관리자 초안 저장(EligibilityRuleSetService.save)을 그대로 타서 검증·잠금·관계형 투영을 함께 한다.
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

    public RuleSet create(long announcementId, DraftFromExtraction request) {
        SavedAnnouncementPdfExtraction latest = extractions.latest(announcementId);
        if (latest == null) {
            throw new EligibilityDraftUnavailableException("PDF 추출 결과가 없습니다. 공고 PDF를 먼저 추출해 주세요.");
        }
        if (!request.pdfSha256().equalsIgnoreCase(latest.pdfSha256())) {
            throw new EligibilityDraftUnavailableException(
                    "화면의 PDF와 최신 추출 결과가 다릅니다. 화면을 새로 고친 뒤 다시 만들어 주세요.");
        }
        String url = sources.attachmentUrl(announcementId, latest.fileName()).orElseThrow(() ->
                new EligibilityDraftUnavailableException(
                        "추출한 PDF(" + latest.fileName() + ")가 공고 첨부에 없어 원문 주소를 알 수 없습니다. 공고 첨부의 PDF로 추출해 주세요."));
        LocalDate referenceDate = sources.postedDate(announcementId).orElseThrow(() ->
                new EligibilityDraftUnavailableException("모집공고일이 없어 신청자격 기준일을 정할 수 없습니다. 공고 정보를 먼저 채워 주세요."));
        List<RegionSearchResult> catalog = regionSearch.findByKeyword("");
        EligibilityContent content = ExtractedEligibilityDraft.build(new Source(
                referenceDate,
                new Document(latest.fileName(), url, latest.extraction().totalPages(), latest.pdfSha256().toLowerCase()),
                sources.units(announcementId).stream().map(this::normalized).toList(),
                reader.read(latest.extraction().json()),
                incomeTables(),
                name -> regionCodes(catalog, name)));
        return ruleSets.save(announcementId, new SaveDraft(request.expectedDraftId(), request.expectedRevision(), content));
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

    /** 공고의 지역 이름을 코드로 바꾼다. 시·도 이름이면 시·도 코드, 시군구 이름이면 그 시군구(구가 나뉜 시는 모든 구) 코드다. */
    static List<String> regionCodes(List<RegionSearchResult> catalog, String name) {
        String wanted = name.replaceAll("\\s+", " ").strip();
        Set<String> codes = new LinkedHashSet<>();
        for (RegionSearchResult region : catalog) {
            if (wanted.equals(region.provinceName())) {
                codes.add(region.regionCode().substring(0, 2));
            } else if (wanted.equals(region.displayName()) || region.displayName().startsWith(wanted + " ")
                    || wanted.equals(region.districtName())
                    || (region.districtName() != null && region.districtName().startsWith(wanted + " "))) {
                codes.add(region.regionCode());
            }
        }
        return List.copyOf(codes);
    }
}
