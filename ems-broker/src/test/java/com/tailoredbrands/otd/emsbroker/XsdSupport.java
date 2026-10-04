package com.tailoredbrands.otd.emsbroker;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Test helper: validates XML against {@code xsd/oms.xsd} (pulled in from legacy-oms-soap via testResources)
 * and parses it into a namespace-aware DOM.
 */
final class XsdSupport {

    static final String NS = "http://tailoredbrands.com/legacy/oms/v1";

    private static Schema schema;

    private XsdSupport() {
    }

    static synchronized Schema schema() throws SAXException {
        if (schema == null) {
            InputStream xsd = XsdSupport.class.getClassLoader().getResourceAsStream("xsd/oms.xsd");
            if (xsd == null) {
                throw new IllegalStateException("xsd/oms.xsd not on the test classpath (see <testResources> in pom.xml)");
            }
            schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                    .newSchema(new StreamSource(xsd, "oms.xsd"));
        }
        return schema;
    }

    static void assertValid(String xml) {
        try {
            schema().newValidator().validate(new StreamSource(new StringReader(xml)));
        } catch (SAXException | IOException e) {
            throw new AssertionError("XML does not validate against oms.xsd: " + e.getMessage() + "\n" + xml, e);
        }
    }

    static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("Cannot parse XML: " + e.getMessage(), e);
        }
    }

    static String text(Document doc, String localName) {
        var nodes = doc.getElementsByTagNameNS(NS, localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }
}
