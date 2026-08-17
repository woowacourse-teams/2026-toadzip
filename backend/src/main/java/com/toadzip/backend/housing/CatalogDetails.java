package com.toadzip.backend.housing;

import java.time.LocalDate;

/**
 * 주택 단지에서 변경 가능한 카탈로그 속성을 묶은 값 객체.
 */
public record CatalogDetails(LocalDate completionDate, String heatingTypeName, Integer parkingSpaces,
		String corridorType, String elevatorInstallation, String houseTypeName) {
}
