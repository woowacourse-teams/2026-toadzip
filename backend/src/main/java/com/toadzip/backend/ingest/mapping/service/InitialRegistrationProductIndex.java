package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.location.domain.NormalizedRoadAddress;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore.Link;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore.Product;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 확인 실행 한 번에 필요한 제품 식별자와 중복 후보를 인덱싱한다. 제품 행은 변경하지 않는다. */
final class InitialRegistrationProductIndex {

    private final Map<String, Product> identifiers = new HashMap<>();
    private final Map<Long, Product> ids = new HashMap<>();
    private final Map<String, List<Product>> candidates = new HashMap<>();
    private final Map<String, Long> links = new HashMap<>();

    InitialRegistrationProductIndex(List<Product> products, List<Link> links) {
        products.forEach(this::add);
        links.forEach(link -> this.links.put(link.identifier(), link.complexId()));
    }

    Product linked(String identifier) {
        if (links.containsKey(identifier)) {
            return ids.get(links.get(identifier));
        }
        return identifiers.get(identifier);
    }

    List<Product> candidates(List<MyHomeComplexSourceView> sources, String preparedAddress) {
        return candidates(sources, preparedAddress, null);
    }

    List<Product> candidates(List<MyHomeComplexSourceView> sources, String preparedAddress, String preparedName) {
        Set<Product> result = new LinkedHashSet<>();
        if (preparedAddress != null && !preparedAddress.isBlank()) {
            result.addAll(candidates.getOrDefault("address:" + address(preparedAddress), List.of()));
        }
        sources.forEach(source -> {
            result.addAll(candidates.getOrDefault("pnu:" + source.getPnu(), List.of()));
            result.addAll(candidates.getOrDefault("address:" + address(source.getRnAdres()), List.of()));
            result.addAll(candidates.getOrDefault("name:" + district(source) + ":" + source.getHsmpNm(),
                    List.of()));
            if (preparedName != null) {
                result.addAll(candidates.getOrDefault("name:" + district(source) + ":" + preparedName, List.of()));
            }
        });
        return List.copyOf(result);
    }

    boolean same(Product product, List<MyHomeComplexSourceView> sources, String supplyType, String provider) {
        return !product.deleted() && product.supplyType().equals(supplyType) && product.provider().equals(provider)
                && sources.stream().allMatch(source -> Objects.equals(product.name(), source.getHsmpNm())
                && address(product.roadAddress()).equals(address(source.getRnAdres()))
                && Objects.equals(product.pnu(), source.getPnu()));
    }

    private void add(Product product) {
        ids.put(product.id(), product);
        identifiers.put(product.identifier(), product);
        addCandidate("pnu:" + product.pnu(), product);
        addCandidate("address:" + address(product.roadAddress()), product);
        addCandidate("name:" + product.district() + ":" + product.name(), product);
    }

    private void addCandidate(String key, Product product) {
        if (key.endsWith(":null") || key.endsWith(":")) {
            return;
        }
        candidates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(product);
    }

    private String address(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return new NormalizedRoadAddress(raw).withoutReference();
    }

    private String district(MyHomeComplexSourceView source) {
        String district = source.getSignguCode();
        if (district != null && district.length() == 3 && source.getBrtcCode() != null) {
            return source.getBrtcCode() + district;
        }
        return district;
    }
}
