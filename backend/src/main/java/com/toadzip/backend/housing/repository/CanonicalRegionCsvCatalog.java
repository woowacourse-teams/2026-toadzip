package com.toadzip.backend.housing.repository;

import java.util.Set;

record CanonicalRegionCsvCatalog(String effectiveDate, Set<String> regionCodes) {
}
