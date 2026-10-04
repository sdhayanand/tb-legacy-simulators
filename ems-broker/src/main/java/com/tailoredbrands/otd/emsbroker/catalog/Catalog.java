package com.tailoredbrands.otd.emsbroker.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The shared {@code catalog.json} (repo root): 25 stores, 40 SKUs, alteration services and order-type weights.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Catalog(
        String ecomStoreNbr,
        Map<String, Integer> orderTypeWeights,
        List<Store> stores,
        List<Sku> skus,
        List<AlterationService> alterations) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Store(String storeNbr, String name, String banner, String state, String region, String tailorShopNbr) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sku(String sku, String description, String category, String banner, BigDecimal price) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AlterationService(String type, String sku, double minInches, double maxInches) {
    }

    public List<Sku> skusInCategory(String category) {
        return skus.stream().filter(s -> category.equals(s.category())).toList();
    }

    public List<Sku> skusInCategories(String... categories) {
        List<String> wanted = List.of(categories);
        return skus.stream().filter(s -> wanted.contains(s.category())).toList();
    }

    public Sku sku(String code) {
        return skus.stream().filter(s -> s.sku().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown SKU " + code));
    }

    public Store store(String storeNbr) {
        return stores.stream().filter(s -> s.storeNbr().equals(storeNbr)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown store " + storeNbr));
    }
}
