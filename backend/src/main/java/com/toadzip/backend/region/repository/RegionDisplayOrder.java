package com.toadzip.backend.region.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/** MOIS province presentation order, then the official code catalog's hierarchy and sibling order. */
public final class RegionDisplayOrder {

    private static final Map<String, String> KEYS = load();

    private RegionDisplayOrder() {
    }

    public static String key(String code) {
        return KEYS.getOrDefault(code, "99." + code);
    }

    private static Map<String, String> load() {
        var resource = new ClassPathResource("region/display-order.csv");
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            if (!"regionCode,sortKey".equals(reader.readLine())) {
                throw new IllegalStateException("Invalid region display order header");
            }
            Map<String, String> keys = new HashMap<>();
            for (String line : reader.lines().toList()) {
                String[] cells = line.split(",", -1);
                if (cells.length != 2 || cells[1].isBlank() || keys.putIfAbsent(cells[0], cells[1]) != null) {
                    throw new IllegalStateException("Invalid region display order row: " + line);
                }
            }
            return Map.copyOf(keys);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read region display order", exception);
        }
    }
}
