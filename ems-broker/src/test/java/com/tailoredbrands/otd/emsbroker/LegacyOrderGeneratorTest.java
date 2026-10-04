package com.tailoredbrands.otd.emsbroker;

import com.tailoredbrands.otd.emsbroker.catalog.Catalog;
import com.tailoredbrands.otd.emsbroker.catalog.CatalogLoader;
import com.tailoredbrands.otd.emsbroker.generator.LegacyOrderGenerator;
import com.tailoredbrands.otd.emsbroker.model.LegacyOrder;
import com.tailoredbrands.otd.emsbroker.xml.LegacyOrderXmlWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyOrderGeneratorTest {

    private static Catalog catalog;

    @BeforeAll
    static void loadCatalog() {
        catalog = CatalogLoader.load(null);
    }

    @Test
    void catalogHasTwentyFiveStoresAndFortySkus() {
        assertThat(catalog.stores()).hasSize(25);
        assertThat(catalog.skus()).hasSize(40);
        assertThat(catalog.stores()).extracting(Catalog.Store::storeNbr).allMatch(s -> s.matches("[0-9]{4}"));
        assertThat(catalog.orderTypeWeights()).containsKeys("R", "T", "X", "E", "C");
    }

    @Test
    void orderTypeDistributionFollowsCatalogWeights() {
        LegacyOrderGenerator generator = new LegacyOrderGenerator(catalog, 42L);
        Map<String, Integer> counts = new HashMap<>();
        int n = 4000;
        for (int i = 0; i < n; i++) {
            counts.merge(generator.next().orderType(), 1, Integer::sum);
        }
        assertThat(counts.keySet()).isSubsetOf(Set.of("R", "T", "X", "E", "C"));
        // weights R50/T25/X15/E7/C3 - allow generous tolerance
        assertThat(counts.getOrDefault("R", 0) / (double) n).isBetween(0.42, 0.58);
        assertThat(counts.getOrDefault("T", 0) / (double) n).isBetween(0.18, 0.32);
        assertThat(counts.getOrDefault("X", 0) / (double) n).isBetween(0.09, 0.21);
        assertThat(counts.getOrDefault("E", 0) / (double) n).isBetween(0.03, 0.12);
        assertThat(counts.getOrDefault("C", 0) / (double) n).isBetween(0.005, 0.07);
    }

    @Test
    void tailoredOrdersCarryAlterationsAndRentalsCarryEventDates() {
        LegacyOrderGenerator generator = new LegacyOrderGenerator(catalog, 7L);

        LegacyOrder tailored = generator.next("T", "0412");
        assertThat(tailored.storeNbr()).isEqualTo("0412");
        assertThat(tailored.hasAlteration()).isTrue();
        assertThat(tailored.lines().get(0).fulfillType()).isEqualTo("P");
        assertThat(tailored.lines()).filteredOn(l -> l.alteration() != null)
                .allSatisfy(l -> {
                    assertThat(l.fulfillType()).isEqualTo("A");
                    assertThat(l.alteration().tailorShopNbr()).isEqualTo("TS-EASTBAY");
                    assertThat(l.alteration().measurementInches()).isPositive();
                });

        LegacyOrder rental = generator.next("X", null);
        assertThat(rental.eventDate()).isAfter(rental.orderDate());
        assertThat(rental.lines()).allMatch(l -> l.sku().contains("RENTAL"));

        LegacyOrder ecom = generator.next("E", null);
        assertThat(ecom.storeNbr()).isEqualTo(catalog.ecomStoreNbr());
        assertThat(ecom.lines()).allMatch(l -> "S".equals(l.fulfillType()));

        LegacyOrder custom = generator.next("C", null);
        assertThat(custom.lines()).hasSize(1);
        assertThat(custom.lines().get(0).sku()).contains("CUSTOM");
    }

    @Test
    void orderNumbersAreUniqueAndFollowThePosFormat() {
        LegacyOrderGenerator generator = new LegacyOrderGenerator(catalog, 1L);
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            LegacyOrder order = generator.next();
            assertThat(order.orderNbr()).matches("[0-9]{4}-[0-9]{6}-[0-9]{6}");
            assertThat(order.correlationId()).startsWith("store-" + order.storeNbr() + "-txn-");
            assertThat(seen.add(order.orderNbr())).as("duplicate order number %s", order.orderNbr()).isTrue();
        }
    }

    @Test
    void generatedXmlValidatesAgainstTheOmsXsd() {
        LegacyOrderGenerator generator = new LegacyOrderGenerator(catalog, 99L);
        for (int i = 0; i < 200; i++) {
            LegacyOrder order = generator.next();
            String xml = LegacyOrderXmlWriter.toXml(order);
            XsdSupport.assertValid(xml);
            Document doc = XsdSupport.parse(xml);
            assertThat(doc.getDocumentElement().getNamespaceURI()).isEqualTo(XsdSupport.NS);
            assertThat(XsdSupport.text(doc, "OrderNbr")).isEqualTo(order.orderNbr());
            assertThat(XsdSupport.text(doc, "StoreNbr")).isEqualTo(order.storeNbr());
        }
        String cancel = LegacyOrderXmlWriter.cancelXml("0412-261003-000871", "0412", java.time.LocalDate.now(), "CUSTOMER_REQUEST");
        XsdSupport.assertValid(cancel);
    }

    @Test
    void sampleXmlDocumentsValidate() {
        for (String sample : SampleXml.all()) {
            XsdSupport.assertValid(sample);
        }
        assertThat(XsdSupport.text(XsdSupport.parse(SampleXml.retail()), "OrderNbr")).isEqualTo(SampleXml.RETAIL_ORDER_NBR);
        assertThat(XsdSupport.text(XsdSupport.parse(SampleXml.tailored()), "OrderType")).isEqualTo("T");
        assertThat(XsdSupport.text(XsdSupport.parse(SampleXml.rental()), "EventDate")).isEqualTo("2026-11-14");
    }
}
