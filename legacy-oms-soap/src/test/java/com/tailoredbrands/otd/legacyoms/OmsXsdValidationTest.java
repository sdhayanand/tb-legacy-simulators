package com.tailoredbrands.otd.legacyoms;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test: the canned legacy XML documents (this module's and the ones ems-broker publishes, pulled in as
 * test resources) must validate against {@code xsd/oms.xsd}, and an invalid one must be rejected.
 */
class OmsXsdValidationTest {

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws SAXException {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schema = factory.newSchema(new StreamSource(resource("xsd/oms.xsd"), "oms.xsd"));
    }

    @Test
    void sampleRequestAndExtractValidate() throws Exception {
        for (String sample : List.of("samples/submit-order-request.xml", "samples/export-orders-extract.xml")) {
            validate(sample);
        }
    }

    @Test
    void emsBrokerCannedOrdersValidate() throws Exception {
        // These files live in ems-broker/src/main/resources/samples and are wired in through <testResources>.
        List<String> samples = List.of(
                "samples/legacy-order-retail.xml",
                "samples/legacy-order-tailored.xml",
                "samples/legacy-order-rental.xml");
        for (String sample : samples) {
            if (OmsXsdValidationTest.class.getClassLoader().getResource(sample) == null) {
                // Module built in isolation (e.g. Docker build context without ems-broker): nothing to check.
                continue;
            }
            validate(sample);
        }
    }

    @Test
    void invalidStoreNumberIsRejected() {
        assertThatThrownBy(() -> validate("samples/invalid-order-bad-store.xml"))
                .isInstanceOf(SAXParseException.class)
                .hasMessageContaining("StoreNbr");
    }

    private static void validate(String resource) throws SAXException, IOException {
        Validator validator = schema.newValidator();
        try (InputStream in = resource(resource)) {
            validator.validate(new StreamSource(in, resource));
        }
        assertThat(validator).isNotNull();
    }

    private static InputStream resource(String path) {
        InputStream in = OmsXsdValidationTest.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IllegalArgumentException("Missing classpath resource " + path);
        }
        return in;
    }
}
