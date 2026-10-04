package com.tailoredbrands.otd.emsbroker.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@code catalog.json}: from {@code CATALOG_PATH} when set, otherwise from the classpath (the Maven build
 * copies the repo-root file into the jar).
 */
@Configuration
public class CatalogLoader {

    private static final Logger log = LoggerFactory.getLogger(CatalogLoader.class);

    @Bean
    public Catalog catalog() {
        return load(System.getenv("CATALOG_PATH"));
    }

    public static Catalog load(String overridePath) {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            Catalog catalog;
            if (overridePath != null && !overridePath.isBlank()) {
                catalog = mapper.readValue(Files.readAllBytes(Path.of(overridePath)), Catalog.class);
                log.info("Loaded catalog from {}", overridePath);
            } else {
                try (InputStream in = CatalogLoader.class.getClassLoader().getResourceAsStream("catalog.json")) {
                    if (in == null) {
                        throw new IllegalStateException("catalog.json not on classpath and CATALOG_PATH not set");
                    }
                    catalog = mapper.readValue(in, Catalog.class);
                }
                log.info("Loaded catalog from classpath");
            }
            validate(catalog);
            log.info("Catalog: {} stores, {} SKUs, {} alteration services", catalog.stores().size(),
                    catalog.skus().size(), catalog.alterations().size());
            return catalog;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read catalog.json", e);
        }
    }

    private static void validate(Catalog catalog) {
        if (catalog.stores() == null || catalog.stores().isEmpty()) {
            throw new IllegalStateException("catalog.json has no stores");
        }
        if (catalog.skus() == null || catalog.skus().isEmpty()) {
            throw new IllegalStateException("catalog.json has no skus");
        }
        if (catalog.alterations() == null || catalog.alterations().isEmpty()) {
            throw new IllegalStateException("catalog.json has no alterations");
        }
        if (catalog.orderTypeWeights() == null || catalog.orderTypeWeights().isEmpty()) {
            throw new IllegalStateException("catalog.json has no orderTypeWeights");
        }
    }
}
