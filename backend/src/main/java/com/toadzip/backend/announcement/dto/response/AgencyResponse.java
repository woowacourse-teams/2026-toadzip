package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.housing.domain.AgencyCode;

public record AgencyResponse(AgencyCode code, String name) {
}
