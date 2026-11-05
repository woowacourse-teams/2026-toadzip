package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.RegionMembership;
import com.toadzip.backend.region.repository.RegionCodeResolver;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class CatalogRegionMembership implements RegionMembership {
    private final RegionCodeResolver resolver;

    public CatalogRegionMembership(RegionCodeResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Match match(Set<String> members, String userRegion) {
        if (!isKnown(userRegion)) {
            return Match.INSUFFICIENT_PRECISION;
        }
        boolean ambiguous = false;
        for (String member : members) {
            Match match = compare(member, userRegion);
            if (match == Match.MATCH) {
                return match;
            }
            ambiguous |= match == Match.INSUFFICIENT_PRECISION;
        }
        if (ambiguous) {
            return Match.INSUFFICIENT_PRECISION;
        }
        return Match.NO_MATCH;
    }

    private Match compare(String member, String userRegion) {
        if (member.length() == 10 && userRegion.length() == 10) {
            return exactMembership(resolver.filterCodes(member).orElse(Set.of()), userRegion);
        }
        Set<String> required = districts(member);
        Set<String> supplied = districts(userRegion);
        if (required.containsAll(supplied)
                && !supplied.isEmpty()
                && member.length() <= userRegion.length()) {
            return Match.MATCH;
        }
        Set<String> overlap = new HashSet<>(required);
        overlap.retainAll(supplied);
        if (!overlap.isEmpty()) {
            return Match.INSUFFICIENT_PRECISION;
        }
        return Match.NO_MATCH;
    }

    private Match exactMembership(Set<String> members, String code) {
        if (members.contains(code)) {
            return Match.MATCH;
        }
        return Match.NO_MATCH;
    }

    private Set<String> districts(String code) {
        if (code.length() == 10) {
            Set<String> result = new HashSet<>();
            for (String equivalent : resolver.filterCodes(code).orElse(Set.of())) {
                result.addAll(
                        resolver.equivalentCodes(equivalent.substring(0, 5)).orElse(Set.of()));
            }
            return result;
        }
        return resolver.filterCodes(code).orElse(Set.of());
    }

    @Override
    public boolean isKnown(String code) {
        return code != null
                && resolver.filterCodes(code).filter(values -> !values.isEmpty()).isPresent();
    }
}
