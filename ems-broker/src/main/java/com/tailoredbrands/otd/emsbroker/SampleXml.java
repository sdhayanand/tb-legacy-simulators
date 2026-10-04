package com.tailoredbrands.otd.emsbroker;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Three canned legacy XML orders (classpath {@code samples/legacy-order-*.xml}).
 *
 * <p>Used by this module's tests, by {@code GET /samples/{name}}, by the OMS module's XSD validation test and
 * referenced from the other repos' docs (bridge XSLT examples, SoapUI / Postman collections).
 */
public final class SampleXml {

    public static final String RETAIL = "samples/legacy-order-retail.xml";
    public static final String TAILORED = "samples/legacy-order-tailored.xml";
    public static final String RENTAL = "samples/legacy-order-rental.xml";

    public static final String RETAIL_ORDER_NBR = "0412-261003-000871";
    public static final String TAILORED_ORDER_NBR = "0875-261003-001204";
    public static final String RENTAL_ORDER_NBR = "1021-261003-000342";

    private SampleXml() {
    }

    /** Retail (R): two take-with lines, walk-in customer (no CustNbr), store 0412 Pleasanton. */
    public static String retail() {
        return load(RETAIL);
    }

    /** Tailored (T): suit plus HEM and SLEEVE alteration work orders for TS-EASTBAY, store 0875 Dublin. */
    public static String tailored() {
        return load(TAILORED);
    }

    /** Rental (X): tuxedo rental with an EventDate, store 1021 Walnut Creek. */
    public static String rental() {
        return load(RENTAL);
    }

    public static List<String> all() {
        return List.of(retail(), tailored(), rental());
    }

    public static String load(String resource) {
        try (InputStream in = SampleXml.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing sample " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read sample " + resource, e);
        }
    }
}
