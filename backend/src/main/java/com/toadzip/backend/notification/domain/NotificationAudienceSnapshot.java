package com.toadzip.backend.notification.domain;

import java.util.List;
import java.util.Set;

public record NotificationAudienceSnapshot(String title, List<ComplexTarget> complexes, List<String> regions) {
    public NotificationAudienceSnapshot {
        complexes = List.copyOf(complexes);
        regions = List.copyOf(regions);
    }

    public boolean matchesRegion(Set<String> codes) {
        if (codes.stream().anyMatch(code -> code.length() == 10)) {
            return complexes.stream().anyMatch(complex -> codes.stream()
                    .anyMatch(code -> complex.legalDongCode().startsWith(code.substring(0, 8))));
        }
        return complexes.stream().anyMatch(complex -> codes.contains(complex.cityCountyDistrictCode()));
    }

    public record ComplexTarget(long id, String provinceCode, String cityCountyDistrictCode, String legalDongCode) {
    }
}
