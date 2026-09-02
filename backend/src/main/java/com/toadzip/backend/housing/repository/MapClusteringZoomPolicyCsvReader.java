package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringZoomPolicy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.core.io.Resource;

final class MapClusteringZoomPolicyCsvReader {

    private static final String EXPECTED_HEADER = "policyVersion,regionDatasetVersion,fromStage,toStage,"
            + "boundaryZoom,hysteresis,expansionZoom";

    MapClusteringZoomPolicy read(Resource resource) {
        List<String> lines = readLines(resource);
        validateHeader(lines, resource);
        return rows(lines, resource).toPolicy();
    }

    private List<MapClusteringPolicyCsvRow> readRows(List<String> lines, Resource resource) {
        return IntStream.range(1, lines.size())
                .filter(index -> isDataRow(lines.get(index)))
                .mapToObj(index -> MapClusteringPolicyCsvRow.parse(lines.get(index), index + 1, resource))
                .toList();
    }

    private MapClusteringPolicyCsvRows rows(List<String> lines, Resource resource) {
        return new MapClusteringPolicyCsvRows(readRows(lines, resource), resource);
    }

    private List<String> readLines(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        } catch (IOException exception) {
            throw MapClusteringPolicyCsvException.readFailure(resource, exception);
        }
    }

    private void validateHeader(List<String> lines, Resource resource) {
        if (!lines.isEmpty() && EXPECTED_HEADER.equals(lines.getFirst())) {
            return;
        }
        throw MapClusteringPolicyCsvException.invalidHeader(resource);
    }

    private boolean isDataRow(String line) {
        return !line.isBlank() && !line.startsWith("#");
    }
}
