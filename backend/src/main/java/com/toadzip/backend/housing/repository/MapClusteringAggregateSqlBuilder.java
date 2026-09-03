package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringRegionAssignment;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

@Component
final class MapClusteringAggregateSqlBuilder {

    private static final String QUERY_START = HousingComplexRepresentativeSql.WITH_CLAUSE + """
            , region_assignment(stored_region_code, group_key) AS (
                VALUES %s
            )
            SELECT region_assignment.group_key,
                   COUNT(DISTINCT housing_complex.id) AS unique_complex_count
            FROM housing_complexes housing_complex
            JOIN region_assignment
              ON region_assignment.stored_region_code = housing_complex.city_county_district_code
            """ + HousingComplexRepresentativeSql.LEFT_JOIN + """
            WHERE 1 = 1
            """;

    private static final String GROUP_AND_ORDER = """
            GROUP BY region_assignment.group_key
            ORDER BY region_assignment.group_key
            """;

    private final HousingComplexFilterPredicateBuilder filterPredicateBuilder;
    private final RegionCodeResolver regionCodeResolver;

    MapClusteringAggregateSqlBuilder(
            HousingComplexFilterPredicateBuilder filterPredicateBuilder,
            RegionCodeResolver regionCodeResolver
    ) {
        this.filterPredicateBuilder = filterPredicateBuilder;
        this.regionCodeResolver = regionCodeResolver;
    }

    MapClusteringAggregateSqlQuery build(
            HousingComplexFilterCondition filters,
            List<MapClusteringRegionAssignment> assignments
    ) {
        HousingComplexFilterPredicate predicate = filterPredicateBuilder.build(filters);
        MapClusteringRegionCodeMappings mappings = MapClusteringRegionCodeMappings.from(
                assignments,
                regionCodeResolver
        );
        requireMappings(mappings);
        Map<String, Object> parameters = mappingParameters(mappings.values());
        parameters.putAll(predicate.parameters());
        String sql = QUERY_START.formatted(valuesSql(mappings.values())) + predicate.sql() + GROUP_AND_ORDER;
        return new MapClusteringAggregateSqlQuery(sql, parameters);
    }

    private String valuesSql(List<MapClusteringRegionCodeMapping> mappings) {
        return IntStream.range(0, mappings.size())
                .mapToObj(index -> "(:storedRegionCode" + index + ", :groupKey" + index + ")")
                .collect(Collectors.joining(",\n                       "));
    }

    private Map<String, Object> mappingParameters(List<MapClusteringRegionCodeMapping> mappings) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        IntStream.range(0, mappings.size())
                .forEach(index -> addMappingParameters(parameters, mappings.get(index), index));
        return parameters;
    }

    private void addMappingParameters(
            Map<String, Object> parameters,
            MapClusteringRegionCodeMapping mapping,
            int index
    ) {
        parameters.put("storedRegionCode" + index, mapping.storedRegionCode());
        parameters.put("groupKey" + index, mapping.groupKey().value());
    }

    private void requireMappings(MapClusteringRegionCodeMappings mappings) {
        if (!mappings.values().isEmpty()) {
            return;
        }
        throw new IllegalArgumentException("Map clustering region assignments are required");
    }
}
