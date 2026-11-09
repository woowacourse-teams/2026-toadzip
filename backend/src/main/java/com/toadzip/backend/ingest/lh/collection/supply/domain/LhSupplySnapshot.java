package com.toadzip.backend.ingest.lh.collection.supply.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class LhSupplySnapshot {

    private LhSupplySnapshot() {
    }

    public static long missingRowCount(
            List<LhAnnouncementSupplySourceView> previous,
            List<LhAnnouncementSupplySourceView> incoming
    ) {
        Map<RowIdentity, List<LhAnnouncementSupplySourceView>> previousGroups = groupsOf(previous);
        Map<RowIdentity, List<LhAnnouncementSupplySourceView>> incomingGroups = groupsOf(incoming);
        return previousGroups.entrySet().stream()
                .mapToLong(entry -> missingCount(
                        entry.getValue(), incomingGroups.getOrDefault(entry.getKey(), List.of())
                ))
                .sum();
    }

    public static String fingerprint(List<LhAnnouncementSupplySourceView> sources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            sources.stream().map(source -> List.of(
                            encoded(source.getComplexLabel()), encoded(source.getTypeName()),
                            encoded(source.getExclusiveArea()), encoded(source.getSupplyArea()),
                            encoded(source.getTotalUnitCount()), encoded(source.getSuppliedUnitCount()),
                            encoded(source.getDepositText()), encoded(source.getMonthlyRentText())
                    )).map(values -> String.join("", values))
                    .sorted()
                    .forEach(value -> digest.update(value.getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("LH 공급 지문 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private static String encoded(String value) {
        if (value == null) {
            return "-1:";
        }
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    private static Map<RowIdentity, List<LhAnnouncementSupplySourceView>> groupsOf(
            List<LhAnnouncementSupplySourceView> sources
    ) {
        return sources.stream()
                .collect(Collectors.groupingBy(RowIdentity::of));
    }

    private static long missingCount(
            List<LhAnnouncementSupplySourceView> previous,
            List<LhAnnouncementSupplySourceView> incoming
    ) {
        Map<RowValues, Long> previousCounts = countsOf(previous);
        if (previousCounts.size() == 1) {
            return Math.max(0, previous.size() - incoming.size());
        }
        Map<RowValues, Long> incomingCounts = countsOf(incoming);
        return previousCounts.entrySet().stream()
                .mapToLong(entry -> Math.max(0, entry.getValue() - incomingCounts.getOrDefault(entry.getKey(), 0L)))
                .sum();
    }

    private static Map<RowValues, Long> countsOf(List<LhAnnouncementSupplySourceView> sources) {
        return sources.stream()
                .map(RowValues::of)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private record RowValues(
            String totalUnitCount,
            String suppliedUnitCount,
            String depositText,
            String monthlyRentText
    ) {

        private static RowValues of(LhAnnouncementSupplySourceView source) {
            return new RowValues(
                    source.getTotalUnitCount(), source.getSuppliedUnitCount(),
                    source.getDepositText(), source.getMonthlyRentText()
            );
        }
    }

    private record RowIdentity(String complexName, String typeName, String exclusiveArea, String supplyArea) {

        private static RowIdentity of(LhAnnouncementSupplySourceView source) {
            return new RowIdentity(
                    source.getComplexLabel(), source.getTypeName(),
                    normalizedArea(source.getExclusiveArea()), normalizedArea(source.getSupplyArea())
            );
        }

        private static String normalizedArea(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.replace(",", "").replace("㎡", "").strip();
            if (!normalized.matches("[0-9]+(?:\\.[0-9]+)?")) {
                return value;
            }
            return new BigDecimal(normalized).stripTrailingZeros().toPlainString();
        }
    }
}
