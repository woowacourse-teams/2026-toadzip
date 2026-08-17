package com.toadzip.backend.ingest.lh;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.toadzip.backend.housing.Address;
import com.toadzip.backend.housing.BaseRentTerms;
import com.toadzip.backend.housing.HousingComplex;
import com.toadzip.backend.housing.HousingComplexRepository;
import com.toadzip.backend.housing.UnitType;
import com.toadzip.backend.housing.UnitTypeRepository;
import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.SourceValues;
import com.toadzip.backend.ingest.lh.source.LhCatalogSource;
import com.toadzip.backend.ingest.lh.source.LhCatalogSourceRepository;
import com.toadzip.backend.ingest.lh.source.LhCatalogSourceStore;
import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** LH 15059475 응답을 typed source로 저장하고 주택형에 제한적으로 보정한다. */
@Slf4j
@Service
public class LhLeaseInfoIngestService {

	private static final String PATH = "lhLeaseInfo1/lhLeaseInfo1";

	private static final String LIST_KEY = "dsList";

	private static final int MAX_PAGE_SIZE = 10_000;

	private static final int MAX_PAGES = 10_000;

	private final DataGoKrOpenApiClient lhApiClient;

	private final ObjectMapper objectMapper;

	private final HousingComplexRepository complexRepository;

	private final UnitTypeRepository unitTypeRepository;

	private final LhCatalogSourceStore sourceStore;

	private final LhCatalogSourceRepository sourceRepository;

	private final TransactionTemplate transactionTemplate;

	@Autowired
	public LhLeaseInfoIngestService(@Qualifier("lhApiClient") DataGoKrOpenApiClient lhApiClient,
			ObjectMapper objectMapper, HousingComplexRepository complexRepository, UnitTypeRepository unitTypeRepository,
			LhCatalogSourceStore sourceStore, LhCatalogSourceRepository sourceRepository,
			PlatformTransactionManager transactionManager) {
		this.lhApiClient = lhApiClient;
		this.objectMapper = objectMapper;
		this.complexRepository = complexRepository;
		this.unitTypeRepository = unitTypeRepository;
		this.sourceStore = sourceStore;
		this.sourceRepository = sourceRepository;
		transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
	}

	public LhLeaseInfoIngestService(DataGoKrOpenApiClient lhApiClient, ObjectMapper objectMapper,
			HousingComplexRepository complexRepository, UnitTypeRepository unitTypeRepository,
			LhCatalogSourceStore sourceStore, PlatformTransactionManager transactionManager) {
		this(lhApiClient, objectMapper, complexRepository, unitTypeRepository, sourceStore, null, transactionManager);
	}

	public LhLeaseInfoIngestService(DataGoKrOpenApiClient lhApiClient, ObjectMapper objectMapper,
			HousingComplexRepository complexRepository, UnitTypeRepository unitTypeRepository,
			PlatformTransactionManager transactionManager, LhCatalogSourceRepository sourceRepository) {
		this(lhApiClient, objectMapper, complexRepository, unitTypeRepository,
				new LhCatalogSourceStore(sourceRepository), sourceRepository, transactionManager);
	}

	public LhLeaseInfoIngestService(DataGoKrOpenApiClient lhApiClient, ObjectMapper objectMapper,
			HousingComplexRepository complexRepository, UnitTypeRepository unitTypeRepository,
			PlatformTransactionManager transactionManager) {
		this(lhApiClient, objectMapper, complexRepository, unitTypeRepository, null, null, transactionManager);
	}

	public IngestReport ingest(int pageSize, int maxPages) {
		validatePaging(pageSize, maxPages);
		try {
			List<JsonNode> pages = fetchPages(pageSize, maxPages);
			List<LhLeaseInfoItem> items = readItems(pages);
			return replaceAndProject(items);
		}
		catch (RuntimeException exception) {
			log.warn("LH 임대 카탈로그 적재에 실패했습니다.", exception);
			return IngestReport.oneFailed();
		}
	}

	/** 외부 API 호출 없이 이미 받은 페이지 응답을 source 스냅샷으로 반영한다. */
	public IngestReport applyPages(List<JsonNode> pages) {
		if (!isCompletePages(pages)) {
			return IngestReport.oneFailed();
		}
		try {
			return replaceAndProject(readItems(pages));
		}
		catch (RuntimeException exception) {
			log.warn("LH 임대 카탈로그 페이지 반영에 실패했습니다.", exception);
			return IngestReport.oneFailed();
		}
	}

	public IngestReport apply(List<JsonNode> pages) {
		return applyPages(pages);
	}

	/** 저장된 typed source만 읽어 주택형 projection을 다시 계산한다. */
	public IngestReport projectAll() {
		if (sourceRepository == null) {
			return IngestReport.oneFailed();
		}
		List<LhCatalogSource> sources = sourceRepository.findAllByOrderBySourceOrderAscIdAsc();
		return applySources(sources);
	}

	/** 저장된 source를 바꾸지 않고 전달된 typed source 행만 projection한다. */
	public IngestReport applySources(List<LhCatalogSource> sources) {
		if (sources == null || sources.isEmpty()) {
			return IngestReport.empty();
		}
		try {
			return Objects.requireNonNull(transactionTemplate.execute(status -> projectRows(sources)));
		}
		catch (RuntimeException exception) {
			log.warn("LH 임대 카탈로그 projection에 실패했습니다.", exception);
			return IngestReport.oneFailed();
		}
	}

	/** typed source snapshot을 교체한다. projection은 실행하지 않는다. */
	public IngestReport replaceSources(List<LhLeaseInfoItem> items) {
		if (sourceStore == null || items == null || items.isEmpty()) {
			return IngestReport.oneFailed();
		}
		return sourceStore.replaceSnapshot(items);
	}

	private IngestReport replaceAndProject(List<LhLeaseInfoItem> items) {
		if (items.isEmpty()) {
			return IngestReport.oneFailed();
		}
		return Objects.requireNonNull(transactionTemplate.execute(status -> {
			if (sourceStore != null) {
				sourceStore.replaceSnapshot(items);
			}
			return projectItems(items);
		}));
	}

	private List<JsonNode> fetchPages(int pageSize, int maxPages) {
		List<JsonNode> pages = new ArrayList<>();
		for (int page = 1; page <= maxPages; page++) {
			MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
			params.add("PG_SZ", String.valueOf(pageSize));
			params.add("PAGE", String.valueOf(page));
			JsonNode root = lhApiClient.getRaw(PATH, params);
			if (!containsDataset(root)) {
				throw new IllegalStateException("LH 응답에 dsList가 없습니다.");
			}
			pages.add(root);
			int rowCount = DataGoKrOpenApiClient.findRows(root, LIST_KEY).size();
			if (rowCount < pageSize) {
				return pages;
			}
		}
		throw new IllegalStateException("LH 임대 카탈로그가 최대 페이지 안에 끝나지 않았습니다.");
	}

	private void validatePaging(int pageSize, int maxPages) {
		if (pageSize < 1 || pageSize > MAX_PAGE_SIZE || maxPages < 1 || maxPages > MAX_PAGES) {
			throw new IllegalArgumentException("페이지 크기와 최대 페이지 수는 1~10000이어야 합니다.");
		}
	}

	private List<LhLeaseInfoItem> readItems(List<JsonNode> pages) {
		List<LhLeaseInfoItem> items = new ArrayList<>();
		for (JsonNode page : pages) {
			for (JsonNode row : DataGoKrOpenApiClient.findRows(page, LIST_KEY)) {
				items.add(objectMapper.convertValue(row, LhLeaseInfoItem.class));
			}
		}
		if (items.isEmpty()) {
			throw new IllegalStateException("LH 응답 dsList가 비어 있습니다.");
		}
		return items;
	}

	private boolean isCompletePages(List<JsonNode> pages) {
		if (pages == null || pages.isEmpty()) {
			return false;
		}
		boolean hasRows = false;
		for (JsonNode page : pages) {
			if (!containsDataset(page)) {
				return false;
			}
			hasRows = hasRows || !DataGoKrOpenApiClient.findRows(page, LIST_KEY).isEmpty();
		}
		return hasRows;
	}

	private boolean containsDataset(JsonNode page) {
		if (page == null || !page.isArray()) {
			return false;
		}
		for (JsonNode element : page) {
			JsonNode dataset = element.path(LIST_KEY);
			if (dataset.isArray()) {
				return true;
			}
		}
		return false;
	}

	private IngestReport projectItems(List<LhLeaseInfoItem> items) {
		List<LhCatalogSource> sources = new ArrayList<>();
		for (int sourceOrder = 0; sourceOrder < items.size(); sourceOrder++) {
			sources.add(new LhCatalogSource(sourceOrder, items.get(sourceOrder)));
		}
		return projectRows(sources);
	}

	private IngestReport projectRows(List<LhCatalogSource> sources) {
		List<LeaseInfoRow> rows = new ArrayList<>();
		for (LhCatalogSource source : sources) {
			rows.add(LeaseInfoRow.from(source));
		}
		if (rows.isEmpty()) {
			return IngestReport.empty();
		}

		Map<CatalogKey, List<HousingComplex>> complexes = complexesByKey();
		Map<Long, List<LeaseInfoRow>> rowsByUnitType = new LinkedHashMap<>();
		Map<Long, UnitType> units = new HashMap<>();
		for (LeaseInfoRow row : rows) {
			UnitType unitType = resolve(row, complexes);
			if (unitType == null) {
				continue;
			}
			rowsByUnitType.computeIfAbsent(unitType.getId(), ignored -> new ArrayList<>()).add(row);
			units.put(unitType.getId(), unitType);
		}

		IngestReport report = IngestReport.empty();
		for (UnitType unitType : unitTypeRepository.findAll()) {
			if (unitType.updateTotalUnitCount(null)) {
				report = report.plus(IngestReport.oneUpdated());
			}
		}

		for (Map.Entry<Long, List<LeaseInfoRow>> entry : rowsByUnitType.entrySet()) {
			if (entry.getValue().size() != 1) {
				continue;
			}
			UnitType unitType = units.get(entry.getKey());
			LeaseInfoRow row = entry.getValue().getFirst();
			boolean changed = unitType.updateTotalUnitCount(row.totalUnitCount());
			changed = updateBaseRentTerms(unitType, row) || changed;
			if (changed) {
				report = report.plus(IngestReport.oneUpdated());
			}
		}
		return report;
	}

	private boolean updateBaseRentTerms(UnitType unitType, LeaseInfoRow row) {
		if (row.deposit() == null && row.monthlyRent() == null) {
			return false;
		}
		BaseRentTerms current = unitType.getBaseRentTerms();
		Long deposit = row.deposit();
		if (deposit == null && current != null) {
			deposit = current.getDeposit();
		}
		Long monthlyRent = row.monthlyRent();
		if (monthlyRent == null && current != null) {
			monthlyRent = current.getMonthlyRent();
		}
		Long convertibleLimit = null;
		if (current != null) {
			convertibleLimit = current.getConvertibleDepositLimit();
		}
		return unitType.updateBaseRentTerms(new BaseRentTerms(deposit, monthlyRent, convertibleLimit));
	}

	private UnitType resolve(LeaseInfoRow row, Map<CatalogKey, List<HousingComplex>> complexes) {
		List<HousingComplex> candidates = complexes.get(row.key());
		if (candidates == null || candidates.size() != 1) {
			return null;
		}
		HousingComplex complex = candidates.getFirst();
		if (!Objects.equals(row.complexTotalUnitCount(), complex.getUnitCount())) {
			return null;
		}
		if (row.exclusiveArea() == null || row.totalUnitCount() == null) {
			return null;
		}
		List<UnitType> unitTypes = unitTypeRepository.findByHousingComplex(complex);
		List<UnitType> matches = new ArrayList<>();
		for (UnitType unitType : unitTypes) {
			if (unitType.getExclusiveArea() != null
					&& unitType.getExclusiveArea().compareTo(row.exclusiveArea()) == 0) {
				matches.add(unitType);
			}
		}
		if (matches.size() != 1) {
			return null;
		}
		return matches.getFirst();
	}

	private Map<CatalogKey, List<HousingComplex>> complexesByKey() {
		Map<CatalogKey, List<HousingComplex>> complexes = new HashMap<>();
		for (HousingComplex complex : complexRepository.findAll()) {
			CatalogKey key = CatalogKey.from(complex);
			if (key == null) {
				continue;
			}
			complexes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(complex);
		}
		return complexes;
	}

	private record LeaseInfoRow(String areaName, String complexLabel, String supplyTypeName,
			Integer complexTotalUnitCount, BigDecimal exclusiveArea, Integer totalUnitCount, Long deposit,
			Long monthlyRent) {

		static LeaseInfoRow from(LhCatalogSource source) {
			return new LeaseInfoRow(SourceValues.trimToNull(source.getAreaName()),
					SourceValues.trimToNull(source.getComplexLabel()), SourceValues.trimToNull(source.getSupplyTypeName()),
					SourceValues.toInt(source.getComplexTotalUnitCount()), SourceValues.toDecimal(source.getExclusiveArea()),
					SourceValues.toInt(source.getTotalUnitCount()), SourceValues.toLong(source.getDepositText()),
					SourceValues.toLong(source.getMonthlyRentText()));
		}

		CatalogKey key() {
			return new CatalogKey(normalize(areaName), normalize(complexLabel), normalize(supplyTypeName));
		}
	}

	private record CatalogKey(String areaName, String complexLabel, String supplyTypeName) {

		static CatalogKey from(HousingComplex complex) {
			Address address = complex.getAddress();
			if (address == null) {
				return null;
			}
			String province = SourceValues.trimToNull(address.getProvinceName());
			String district = SourceValues.trimToNull(address.getDistrictName());
			if (province == null || district == null) {
				return null;
			}
			String areaName = province + " " + district;
			CatalogKey key = new CatalogKey(normalize(areaName), normalize(complex.getName()),
					normalize(complex.getSupplyTypeName()));
			if (!key.complete()) {
				return null;
			}
			return key;
		}

		boolean complete() {
			return areaName != null && complexLabel != null && supplyTypeName != null;
		}
	}

	private static String normalize(String value) {
		String trimmed = SourceValues.trimToNull(value);
		if (trimmed == null) {
			return null;
		}
		return trimmed.replaceAll("\\s+", "");
	}

}
