package com.toadzip.backend.housing.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.core.io.Resource;

final class MapClusteringCsvContent {

    private final Resource resource;
    private final List<String> lines;

    private MapClusteringCsvContent(Resource resource, List<String> lines) {
        this.resource = resource;
        this.lines = lines;
    }

    static MapClusteringCsvContent read(Resource resource, String expectedHeader) {
        List<String> lines = readLines(resource);
        validateHeader(lines, resource, expectedHeader);
        return new MapClusteringCsvContent(resource, lines);
    }

    List<MapClusteringCsvLine> dataRows() {
        return IntStream.range(1, lines.size())
                .filter(index -> isDataRow(lines.get(index)))
                .mapToObj(index -> new MapClusteringCsvLine(lines.get(index), index + 1))
                .toList();
    }

    String requiredMetadata(String name) {
        String prefix = "# " + name + "=";
        return lines.stream()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseThrow(() -> missingMetadata(name));
    }

    Resource resource() {
        return resource;
    }

    private IllegalStateException missingMetadata(String name) {
        return new IllegalStateException(
                "Missing map clustering CSV metadata '" + name + "' in " + resource.getDescription()
        );
    }

    private static List<String> readLines(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        } catch (IOException exception) {
            throw MapClusteringPolicyCsvException.readFailure(resource, exception);
        }
    }

    private static void validateHeader(List<String> lines, Resource resource, String expectedHeader) {
        if (!lines.isEmpty() && expectedHeader.equals(lines.getFirst())) {
            return;
        }
        throw MapClusteringPolicyCsvException.invalidHeader(resource);
    }

    private static boolean isDataRow(String line) {
        return !line.isBlank() && !line.startsWith("#");
    }
}

record MapClusteringCsvLine(String value, int lineNumber) {
}
