package com.toadzip.backend.ingest.lh;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.toadzip.backend.ingest.SourceValues;

@Component
public class LhSupplyInfoTypeResolver {

	private static final Map<String, String> CODE_BY_NAME = Map.of("5년임대", "060", "10년임대", "060", "50년임대",
			"061", "국민임대", "062", "영구임대", "062", "행복주택", "063");

	private static final Set<String> NOT_CONSTRUCTION_RENTAL = Set.of("매입임대", "전세임대");

	public Optional<String> resolve(String supplyTypeName) {
		String name = SourceValues.trimToNull(supplyTypeName);
		if (name != null && NOT_CONSTRUCTION_RENTAL.contains(name)) {
			throw new IllegalArgumentException("건설임대가 아닌 공급유형입니다: " + name);
		}
		return Optional.ofNullable(name).map(CODE_BY_NAME::get);
	}
}
