package com.toadzip.backend.admin.dto;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminSearch(@Size(max = 200) String keyword, AgencyCode provider, RentalType rental,
        @Pattern(regexp = "[0-9]{0,10}") String region, Boolean deleted, Boolean review,
        @Min(0) @Max(100000) Integer page, @Min(1) @Max(100) Integer size) {
    public AdminSearch {
        if (deleted == null) { deleted = false; }
        if (review == null) { review = false; }
        if (page == null) { page = 0; }
    }
    public String pattern() {
        if (keyword == null) { return "%"; }
        return "%" + keyword.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    public String identifier() { return keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT); }
    public int pageSize() { if (size == null) { return 20; } return size; }
    public String providerCode() { if (provider == null) { return ""; } return provider.name(); }
    public String providerLegacy() { if (provider == null) { return ""; } return provider.legacyStoredValue(); }
    public String rentalCode() { if (rental == null) { return ""; } return rental.name(); }
    public String rentalLegacy() { if (rental == null) { return ""; } return rental.legacyStoredValue(); }
    public String regionCode() { if (region == null) { return ""; } return region; }
}
