package com.toadzip.backend.housing.dto.response;

import java.util.List;

public record HousingTypeImpact(String token, List<AffectedSupply> rows) {
    public record AffectedSupply(long announcementId, String announcementName, long supplyRowId,
            boolean excluded, boolean areaProtected, boolean reviewedAreas, long announcementVersion) { }
}
