package com.tailoredbrands.otd.erpmq.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailoredbrands.otd.erpmq.store.ReceivedMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a message body into a {@link ReceivedMessage} summary. Understands:
 * <ul>
 *   <li>the legacy {@code <Order>} / {@code <OrderCancel>} XML that TIBCO BW puts on the queue today, and</li>
 *   <li>the canonical {@code OrderEvent} JSON envelope, should the pubsub-to-jms-bridge forward it untransformed.</li>
 * </ul>
 * Anything else is kept as {@code UNKNOWN} so nothing is lost.
 */
public class OrderMessageParser {

    public static final String LEGACY_NS = "http://tailoredbrands.com/legacy/oms/v1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReceivedMessage parse(String body, String messageId, String correlationId, Instant receivedAt) {
        String trimmed = body == null ? "" : body.strip();
        try {
            if (trimmed.startsWith("<")) {
                return parseXml(trimmed, messageId, correlationId, receivedAt);
            }
            if (trimmed.startsWith("{")) {
                return parseJson(trimmed, messageId, correlationId, receivedAt);
            }
        } catch (RuntimeException e) {
            // fall through to UNKNOWN
        }
        return new ReceivedMessage(messageId, correlationId, receivedAt, "UNKNOWN", "UNKNOWN",
                null, null, null, null, null, 0, BigDecimal.ZERO, List.of(), body);
    }

    private ReceivedMessage parseXml(String xml, String messageId, String correlationId, Instant receivedAt) {
        Document doc = parseDocument(xml);
        Element root = doc.getDocumentElement();
        String rootName = root.getLocalName() == null ? root.getTagName() : root.getLocalName();

        if ("OrderCancel".equals(rootName)) {
            return new ReceivedMessage(messageId, correlationId, receivedAt, "LEGACY_XML", "ORDER_CANCELLED",
                    text(root, "OrderNbr"), null, text(root, "StoreNbr"), null, text(root, "CancelDate"),
                    0, BigDecimal.ZERO, List.of(), xml);
        }

        // <Order> (possibly wrapped, e.g. <SubmitOrderRequest><Order>): find the first Order element
        Element order = "Order".equals(rootName) ? root : firstElement(root, "Order");
        if (order == null) {
            return new ReceivedMessage(messageId, correlationId, receivedAt, "UNKNOWN", "UNKNOWN",
                    null, null, null, null, null, 0, BigDecimal.ZERO, List.of(), xml);
        }
        List<String> skus = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        NodeList lines = elements(order, "Line");
        for (int i = 0; i < lines.getLength(); i++) {
            Element line = (Element) lines.item(i);
            String sku = text(line, "SKU");
            if (sku != null) {
                skus.add(sku);
            }
            BigDecimal price = decimal(text(line, "Price"));
            int qty = integer(text(line, "Qty"), 1);
            total = total.add(price.multiply(BigDecimal.valueOf(qty)));
        }
        String status = text(order, "Status");
        String eventType = "CANCELLED".equals(status) ? "ORDER_CANCELLED" : "ORDER_CREATED";
        return new ReceivedMessage(messageId, correlationId, receivedAt, "LEGACY_XML", eventType,
                text(order, "OrderNbr"), text(order, "OrderType"), text(order, "StoreNbr"), text(order, "CustNbr"),
                text(order, "OrderDate"), lines.getLength(), total.setScale(2, RoundingMode.HALF_UP), skus, xml);
    }

    private ReceivedMessage parseJson(String json, String messageId, String correlationId, Instant receivedAt) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not JSON", e);
        }
        JsonNode order = root.has("order") ? root.get("order") : root;
        List<String> skus = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        JsonNode lines = order.path("lines");
        for (JsonNode line : lines) {
            if (line.hasNonNull("sku")) {
                skus.add(line.get("sku").asText());
            }
            BigDecimal price = line.hasNonNull("unitPrice") ? line.get("unitPrice").decimalValue() : BigDecimal.ZERO;
            int qty = line.path("quantity").asInt(1);
            total = total.add(price.multiply(BigDecimal.valueOf(qty)));
        }
        if (order.hasNonNull("totalAmount")) {
            total = order.get("totalAmount").decimalValue();
        }
        String eventType = root.path("eventType").asText("ORDER_CREATED");
        String orderType = order.path("orderType").asText(null);
        return new ReceivedMessage(messageId,
                root.hasNonNull("correlationId") ? root.get("correlationId").asText() : correlationId,
                receivedAt, "CANONICAL_JSON", eventType,
                order.path("orderId").asText(null), toLegacyType(orderType), order.path("storeId").asText(null),
                order.path("customerId").asText(null), order.path("orderedAt").asText(null),
                lines.isArray() ? lines.size() : 0, total.setScale(2, RoundingMode.HALF_UP), skus, json);
    }

    /** Canonical order types back to the one-letter codes the ERP keys on. */
    static String toLegacyType(String canonical) {
        if (canonical == null) {
            return null;
        }
        return switch (canonical) {
            case "RETAIL" -> "R";
            case "TAILORED" -> "T";
            case "CUSTOM" -> "C";
            case "RENTAL" -> "X";
            case "ECOM" -> "E";
            default -> canonical;
        };
    }

    private static Document parseDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not well-formed XML: " + e.getMessage(), e);
        }
    }

    private static NodeList elements(Element parent, String localName) {
        NodeList namespaced = parent.getElementsByTagNameNS(LEGACY_NS, localName);
        if (namespaced.getLength() > 0) {
            return namespaced;
        }
        return parent.getElementsByTagName(localName);
    }

    private static Element firstElement(Element parent, String localName) {
        NodeList nodes = elements(parent, localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String text(Element parent, String localName) {
        NodeList nodes = elements(parent, localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal decimal(String value) {
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static int integer(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
