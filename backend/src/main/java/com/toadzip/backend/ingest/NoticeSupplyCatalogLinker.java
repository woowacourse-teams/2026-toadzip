package com.toadzip.backend.ingest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.toadzip.backend.housing.HousingComplex;
import com.toadzip.backend.housing.HousingComplexRepository;
import com.toadzip.backend.housing.UnitType;
import com.toadzip.backend.housing.UnitTypeRepository;
import com.toadzip.backend.notice.Notice;
import com.toadzip.backend.notice.NoticeRepository;
import com.toadzip.backend.notice.NoticeSupply;
import com.toadzip.backend.notice.NoticeSupplyRepository;

/** 공급행에 PNU·공급유형으로 찾은 카탈로그 단지와 주택형을 연결한다. */
@Slf4j
@Service
public class NoticeSupplyCatalogLinker {

	private static final BigDecimal AREA_TOLERANCE = new BigDecimal("0.05");

	private final NoticeRepository noticeRepository;

	private final NoticeSupplyRepository supplyRepository;

	private final HousingComplexRepository complexRepository;

	private final UnitTypeRepository unitTypeRepository;

	private final TransactionTemplate transactionTemplate;

	public NoticeSupplyCatalogLinker(NoticeRepository noticeRepository, NoticeSupplyRepository supplyRepository,
			HousingComplexRepository complexRepository, UnitTypeRepository unitTypeRepository,
			PlatformTransactionManager transactionManager) {
		this.noticeRepository = noticeRepository;
		this.supplyRepository = supplyRepository;
		this.complexRepository = complexRepository;
		this.unitTypeRepository = unitTypeRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/** 모든 공고의 공급행을 다시 연결한다. */
	public IngestReport linkAll() {
		IngestReport report = IngestReport.empty();
		for (Notice notice : noticeRepository.findAll()) {
			try {
				report = report.plus(link(notice.getId()));
			}
			catch (RuntimeException exception) {
				log.warn("공급행 카탈로그 연결에 실패했습니다: noticeId={}", notice.getId(), exception);
				report = report.plus(IngestReport.oneFailed());
			}
		}
		return report;
	}

	/** 공고 하나의 공급행을 다시 연결한다. 기존 FK와 미연결 사유도 재계산한다. */
	public IngestReport link(Long noticeId) {
		return Objects.requireNonNull(transactionTemplate.execute(status -> linkInTransaction(noticeId)));
	}

	private IngestReport linkInTransaction(Long noticeId) {
		Notice notice = noticeRepository.findById(noticeId)
				.orElseThrow(() -> new IllegalArgumentException("존재하지 않는 공고입니다: " + noticeId));
		boolean changed = false;
		for (NoticeSupply supply : supplyRepository.findByNoticeIdOrderByDisplayOrder(noticeId)) {
			Long previousComplexId = idOf(supply.getHousingComplex());
			Long previousUnitTypeId = idOf(supply.getUnitType());
			String previousReason = supply.getUnmatchedReason();
			resolve(notice, supply);
			changed = changed || !Objects.equals(previousComplexId, idOf(supply.getHousingComplex()))
					|| !Objects.equals(previousUnitTypeId, idOf(supply.getUnitType()))
					|| !Objects.equals(previousReason, supply.getUnmatchedReason());
			supplyRepository.save(supply);
		}
		return changed ? IngestReport.oneUpdated() : IngestReport.oneUnchanged();
	}

	private void resolve(Notice notice, NoticeSupply supply) {
		HousingComplex complex = resolveComplex(notice, supply);
		if (complex == null) {
			return;
		}

		BigDecimal area = supply.getExclusiveArea();
		if (area == null) {
			supply.linkCatalog(complex, null, notice.getLhFetchedAt() == null
					? "LH 주택형별 공급정보를 아직 받지 않은 공고"
					: "LH 응답에 이 단지의 주택형 행이 없음");
			return;
		}

		List<UnitType> candidates = unitTypeRepository.findByHousingComplex(complex).stream()
				.filter(unitType -> unitType.getExclusiveArea() != null)
				.filter(unitType -> unitType.getExclusiveArea().subtract(area).abs().compareTo(AREA_TOLERANCE) <= 0)
				.toList();
		if (candidates.isEmpty()) {
			supply.linkCatalog(complex, null, "전용면적 %s㎡ 근처의 카탈로그 주택형 없음".formatted(area));
			return;
		}
		if (candidates.size() == 1) {
			supply.linkCatalog(complex, candidates.getFirst(), null);
			return;
		}

		UnitType exact = onlyExactAreaMatch(candidates, area);
		if (exact != null) {
			supply.linkCatalog(complex, exact, null);
			return;
		}
		UnitType bySupplyArea = onlySupplyAreaMatch(candidates, supply.getSupplyArea());
		if (bySupplyArea != null) {
			supply.linkCatalog(complex, bySupplyArea, null);
			return;
		}
		supply.linkCatalog(complex, null, "전용면적 %s㎡ 근처 카탈로그 주택형 후보 %d건".formatted(area, candidates.size()));
	}

	private HousingComplex resolveComplex(Notice notice, NoticeSupply supply) {
		String pnu = supply.getSuppliedPnu();
		String supplyTypeName = notice.getSupplyTypeName();
		if (pnu == null || supplyTypeName == null) {
			supply.linkCatalog(null, null, supply.getHouseSn() == null
					? "주소가 안 맞아 공고 공급행에 못 붙인 LH 공급행"
					: "공고 공급행에 PNU가 없음");
			return null;
		}
		List<HousingComplex> candidates = complexRepository.findAllByAddressPnuAndSupplyTypeName(pnu, supplyTypeName);
		if (candidates.isEmpty()) {
			supply.linkCatalog(null, null, "PNU·공급유형으로 찾은 카탈로그 단지 없음");
			return null;
		}
		if (candidates.size() > 1) {
			supply.linkCatalog(null, null, "PNU·공급유형 카탈로그 단지 후보 %d건".formatted(candidates.size()));
			return null;
		}
		return candidates.getFirst();
	}

	private UnitType onlyExactAreaMatch(List<UnitType> candidates, BigDecimal area) {
		List<UnitType> exact = candidates.stream()
				.filter(unitType -> unitType.getExclusiveArea().compareTo(area) == 0)
				.toList();
		return exact.size() == 1 ? exact.getFirst() : null;
	}

	private UnitType onlySupplyAreaMatch(List<UnitType> candidates, BigDecimal supplyArea) {
		if (supplyArea == null) {
			return null;
		}
		List<UnitType> matched = candidates.stream()
				.filter(unitType -> unitType.getResidentialCommonArea() != null)
				.filter(unitType -> unitType.getExclusiveArea().add(unitType.getResidentialCommonArea())
						.subtract(supplyArea).abs().compareTo(AREA_TOLERANCE) <= 0)
				.toList();
		return matched.size() == 1 ? matched.getFirst() : null;
	}

	private Long idOf(HousingComplex complex) {
		return complex == null ? null : complex.getId();
	}

	private Long idOf(UnitType unitType) {
		return unitType == null ? null : unitType.getId();
	}
}
