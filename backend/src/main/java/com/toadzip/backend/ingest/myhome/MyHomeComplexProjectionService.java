package com.toadzip.backend.ingest.myhome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.toadzip.backend.housing.Address;
import com.toadzip.backend.housing.BaseRentTerms;
import com.toadzip.backend.housing.CatalogDetails;
import com.toadzip.backend.housing.HousingComplex;
import com.toadzip.backend.housing.HousingComplexRepository;
import com.toadzip.backend.housing.UnitType;
import com.toadzip.backend.housing.UnitTypeRepository;
import com.toadzip.backend.ingest.ConstructionRentalPolicy;
import com.toadzip.backend.ingest.IngestRejectionReason;
import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.SourceValues;
import com.toadzip.backend.ingest.myhome.source.MyHomeComplexSource;
import com.toadzip.backend.ingest.myhome.source.MyHomeComplexSourceRepository;

@Slf4j
@Service
public class MyHomeComplexProjectionService {

	private final MyHomeComplexSourceRepository sourceRepository;

	private final HousingComplexRepository complexRepository;

	private final UnitTypeRepository unitTypeRepository;

	private final ConstructionRentalPolicy rentalPolicy;

	private final TransactionTemplate transactionTemplate;

	public MyHomeComplexProjectionService(MyHomeComplexSourceRepository sourceRepository,
			HousingComplexRepository complexRepository, UnitTypeRepository unitTypeRepository,
			ConstructionRentalPolicy rentalPolicy, PlatformTransactionManager transactionManager) {
		this.sourceRepository = sourceRepository;
		this.complexRepository = complexRepository;
		this.unitTypeRepository = unitTypeRepository;
		this.rentalPolicy = rentalPolicy;
		transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public IngestReport projectAll() {
		List<MyHomeComplexSourceItem> items = sourceRepository.findAll()
			.stream()
			.map(MyHomeComplexSource::toItem)
			.toList();
		return project(items);
	}

	private IngestReport project(List<MyHomeComplexSourceItem> items) {
		IngestReport report = IngestReport.empty();
		Map<ComplexSupplyKey, List<MyHomeComplexSourceItem>> groups = new LinkedHashMap<>();
		for (MyHomeComplexSourceItem item : items) {
			Optional<IngestRejectionReason> rejection = rentalPolicy.rejectSupplyType(item.suplyTyNm());
			if (rejection.isPresent()) {
				report = report.plus(IngestReport.oneRejected(rejection.orElseThrow()));
				continue;
			}
			if (item.hsmpSn() == null) {
				report = report.plus(IngestReport.oneRejected(IngestRejectionReason.MISSING_IDENTITY));
				continue;
			}
			ComplexSupplyKey key = new ComplexSupplyKey(String.valueOf(item.hsmpSn()),
					SourceValues.trimToNull(item.suplyTyNm()));
			groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
		}

		try {
			IngestReport projected = transactionTemplate.execute(status -> projectGroups(groups));
			return report.plus(Objects.requireNonNull(projected));
		}
		catch (RuntimeException exception) {
			log.warn("마이홈 단지 일괄 투영 실패", exception);
			return report.plus(IngestReport.oneFailed());
		}
	}

	private IngestReport projectGroups(Map<ComplexSupplyKey, List<MyHomeComplexSourceItem>> groups) {
		IngestReport report = IngestReport.empty();
		for (Map.Entry<ComplexSupplyKey, List<MyHomeComplexSourceItem>> entry : groups.entrySet()) {
			report = report.plus(project(entry.getKey(), entry.getValue()));
		}
		return report;
	}

	private IngestReport project(ComplexSupplyKey key, List<MyHomeComplexSourceItem> items) {
		try {
			return projectAggregate(key, items);
		}
		catch (RuntimeException exception) {
			log.warn("마이홈 단지 투영 실패: sourceComplexId={}, supplyType={}", key.sourceComplexId(), key.supplyTypeName(),
					exception);
			return IngestReport.oneFailed();
		}
	}

	private IngestReport projectAggregate(ComplexSupplyKey key, List<MyHomeComplexSourceItem> items) {
		Optional<IngestRejectionReason> rejection = validate(items);
		if (rejection.isPresent()) {
			return IngestReport.oneRejected(rejection.orElseThrow());
		}

		AggregateText name = aggregateText(items, MyHomeComplexSourceItem::hsmpNm);
		AggregateText roadAddress = aggregateText(items, MyHomeComplexSourceItem::rnAdres);
		AggregateText institution = aggregateText(items, MyHomeComplexSourceItem::insttNm);
		MyHomeComplexSourceItem head = items.stream()
			.filter(item -> roadAddress.value().equals(SourceValues.trimToNull(item.rnAdres())))
			.findFirst()
			.orElseThrow();
		Integer unitCount = distinctValues(items, MyHomeComplexSourceItem::hshldCo).stream().findFirst().orElse(null);

		Optional<HousingComplex> stored = complexRepository
			.findBySourceComplexIdAndSupplyTypeName(key.sourceComplexId(), key.supplyTypeName());
		HousingComplex complex = stored.orElse(null);
		boolean created = complex == null;
		if (created) {
			complex = new HousingComplex(name.value(), addressOf(head, roadAddress.value()), key.sourceComplexId(),
					key.supplyTypeName(), unitCount, institution.value());
		}

		boolean complexChanged = complex.updateCatalogDetails(new CatalogDetails(SourceValues.toDate(head.competDe()),
				SourceValues.trimToNull(head.heatMthdDetailNm()), head.parkngCo(),
				SourceValues.trimToNull(head.buldStleNm()), SourceValues.trimToNull(head.elvtrInstlAtNm()),
				SourceValues.trimToNull(head.houseTyNm())));
		if (complex.updateSupplyDetails(unitCount, institution.value())) {
			complexChanged = true;
		}
		if (created || complexChanged) {
			complex = complexRepository.save(complex);
		}

		UnitProjection unitProjection;
		try {
			unitProjection = projectUnitTypes(complex, items);
		}
		catch (RuntimeException exception) {
			if (created) {
				complexRepository.delete(complex);
			}
			throw exception;
		}
		IngestReport storedReport = IngestReport.oneUnchanged();
		if (created) {
			storedReport = IngestReport.oneCreated();
		}
		if (!created && (complexChanged || unitProjection.changed())) {
			storedReport = IngestReport.oneUpdated();
		}
		return storedReport.plus(unitProjection.rejections());
	}

	private Optional<IngestRejectionReason> validate(List<MyHomeComplexSourceItem> items) {
		boolean constructionHousing = items.stream()
			.anyMatch(item -> rentalPolicy.hasConstructionEvidence(item.houseTyNm(), item.competDe()));
		if (!constructionHousing) {
			return Optional.of(IngestRejectionReason.NOT_CONSTRUCTION_HOUSING);
		}

		List<AggregateText> required = List.of(aggregateText(items, MyHomeComplexSourceItem::hsmpNm),
				aggregateText(items, MyHomeComplexSourceItem::rnAdres),
				aggregateText(items, MyHomeComplexSourceItem::insttNm));
		if (required.stream().anyMatch(AggregateText::missing)) {
			return Optional.of(IngestRejectionReason.MISSING_IDENTITY);
		}
		if (required.stream().anyMatch(AggregateText::conflicted)) {
			return Optional.of(IngestRejectionReason.INVALID_SOURCE_ROW);
		}
		if (distinctValues(items, MyHomeComplexSourceItem::hshldCo).size() > 1) {
			return Optional.of(IngestRejectionReason.INVALID_SOURCE_ROW);
		}
		boolean hasUnitType = items.stream().anyMatch(item -> SourceValues.trimToNull(item.styleNm()) != null);
		if (!hasUnitType) {
			return Optional.of(IngestRejectionReason.INVALID_SOURCE_ROW);
		}
		return Optional.empty();
	}

	private UnitProjection projectUnitTypes(HousingComplex complex, List<MyHomeComplexSourceItem> items) {
		Map<UnitKey, MyHomeComplexSourceItem> distinct = new LinkedHashMap<>();
		IngestReport rejections = IngestReport.empty();
		for (MyHomeComplexSourceItem item : items) {
			String typeName = SourceValues.trimToNull(item.styleNm());
			if (typeName == null) {
				rejections = rejections.plus(IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));
				continue;
			}
			distinct.putIfAbsent(new UnitKey(typeName, item.suplyPrvuseAr(), item.suplyCmnuseAr()), item);
		}

		Map<UnitKey, UnitType> storedByKey = unitTypeRepository.findByHousingComplex(complex)
			.stream()
			.collect(Collectors.toMap(this::unitKeyOf, unitType -> unitType, (first, second) -> first,
					LinkedHashMap::new));
		List<UnitType> changedUnitTypes = new ArrayList<>();
		boolean changed = false;
		for (Map.Entry<UnitKey, MyHomeComplexSourceItem> entry : distinct.entrySet()) {
			UnitType unitType = storedByKey.get(entry.getKey());
			boolean created = unitType == null;
			if (created) {
				unitType = new UnitType(complex, entry.getKey().typeName(), entry.getKey().exclusiveArea(),
						entry.getKey().residentialCommonArea());
			}
			boolean unitTypeChanged = unitType.updateBaseRentTerms(rentTermsOf(entry.getValue()));
			if (created || unitTypeChanged) {
				changedUnitTypes.add(unitType);
				changed = true;
			}
		}
		if (!changedUnitTypes.isEmpty()) {
			unitTypeRepository.saveAll(changedUnitTypes);
		}
		return new UnitProjection(changed, rejections);
	}

	private UnitKey unitKeyOf(UnitType unitType) {
		return new UnitKey(unitType.getTypeName(), unitType.getExclusiveArea(), unitType.getResidentialCommonArea());
	}

	private static BigDecimal normalizeArea(BigDecimal area) {
		return area == null ? null : area.setScale(4, RoundingMode.HALF_UP);
	}

	private BaseRentTerms rentTermsOf(MyHomeComplexSourceItem item) {
		if (item.bassRentGtn() == null && item.bassMtRntchrg() == null && item.bassCnvrsGtnLmt() == null) {
			return null;
		}
		return new BaseRentTerms(item.bassRentGtn(), item.bassMtRntchrg(), item.bassCnvrsGtnLmt());
	}

	private Address addressOf(MyHomeComplexSourceItem item, String roadAddress) {
		return new Address(roadAddress, item.pnu(), SourceValues.trimToNull(item.brtcCode()),
				SourceValues.trimToNull(item.brtcNm()), SourceValues.trimToNull(item.signguCode()),
				SourceValues.trimToNull(item.signguNm()));
	}

	private AggregateText aggregateText(List<MyHomeComplexSourceItem> items,
			Function<MyHomeComplexSourceItem, String> extractor) {
		Set<String> values = distinctValues(items, item -> SourceValues.trimToNull(extractor.apply(item)));
		if (values.isEmpty()) {
			return new AggregateText(null, false);
		}
		if (values.size() > 1) {
			return new AggregateText(null, true);
		}
		return new AggregateText(values.iterator().next(), false);
	}

	private <T> Set<T> distinctValues(List<MyHomeComplexSourceItem> items,
			Function<MyHomeComplexSourceItem, T> extractor) {
		Set<T> values = new LinkedHashSet<>();
		for (MyHomeComplexSourceItem item : items) {
			T value = extractor.apply(item);
			if (value != null) {
				values.add(value);
			}
		}
		return values;
	}

	private record ComplexSupplyKey(String sourceComplexId, String supplyTypeName) {
	}

	private record UnitKey(String typeName, BigDecimal exclusiveArea, BigDecimal residentialCommonArea) {

		private UnitKey {
			exclusiveArea = normalizeArea(exclusiveArea);
			residentialCommonArea = normalizeArea(residentialCommonArea);
		}
	}

	private record UnitProjection(boolean changed, IngestReport rejections) {
	}

	private record AggregateText(String value, boolean conflicted) {

		boolean missing() {
			return value == null && !conflicted;
		}

	}

}
