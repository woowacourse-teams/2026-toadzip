package com.toadzip.backend.ingest;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class ConstructionRentalPolicy {

	private static final Set<String> CONSTRUCTION_RENTAL = Set.of("영구임대", "국민임대", "행복주택", "통합공공임대", "5년임대", "10년임대",
			"50년임대");

	private static final Set<String> KNOWN_BUT_EXCLUDED = Set.of("매입임대", "전세임대", "장기전세");

	private static final String APARTMENT = "아파트";

	public Optional<IngestRejectionReason> rejectSupplyType(String sourceLabel) {
		String label = SourceValues.trimToNull(sourceLabel);
		if (label == null) {
			return Optional.of(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
		}
		if (KNOWN_BUT_EXCLUDED.contains(label)) {
			return Optional.of(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE);
		}
		if (!CONSTRUCTION_RENTAL.contains(label)) {
			return Optional.of(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
		}
		return Optional.empty();
	}

	public boolean hasConstructionEvidence(String houseTypeLabel, String completionDate) {
		if (APARTMENT.equals(SourceValues.trimToNull(houseTypeLabel))) {
			return true;
		}
		return SourceValues.toDate(completionDate) != null;
	}

}
