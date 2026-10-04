package com.tailoredbrands.otd.emsbroker.xml;

import com.tailoredbrands.otd.emsbroker.model.LegacyOrder;
import com.tailoredbrands.otd.emsbroker.model.LegacyOrder.LegacyOrderLine;

import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Serialises a {@link LegacyOrder} to the legacy {@code <Order>} XML (namespace
 * {@code http://tailoredbrands.com/legacy/oms/v1}, element order exactly as in {@code oms.xsd}).
 *
 * <p>Hand-written rather than JAXB on purpose: the BW process builds the message with an XML mapper
 * activity, and this module must not depend on the OMS module's generated classes.
 */
public final class LegacyOrderXmlWriter {

    public static final String NAMESPACE = "http://tailoredbrands.com/legacy/oms/v1";

    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private LegacyOrderXmlWriter() {
    }

    public static String toXml(LegacyOrder order) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(XML_DECL);
        sb.append("<Order xmlns=\"").append(NAMESPACE).append("\">\n");
        element(sb, 1, "OrderNbr", order.orderNbr());
        element(sb, 1, "OrderType", order.orderType());
        element(sb, 1, "StoreNbr", order.storeNbr());
        if (order.custNbr() != null) {
            element(sb, 1, "CustNbr", order.custNbr());
        }
        element(sb, 1, "OrderDate", order.orderDate().toString());
        if (order.eventDate() != null) {
            element(sb, 1, "EventDate", order.eventDate().toString());
        }
        if (order.status() != null) {
            element(sb, 1, "Status", order.status());
        }
        indent(sb, 1).append("<Lines>\n");
        for (LegacyOrderLine line : order.lines()) {
            indent(sb, 2).append("<Line>\n");
            element(sb, 3, "LineNbr", Integer.toString(line.lineNbr()));
            element(sb, 3, "SKU", line.sku());
            element(sb, 3, "Qty", Integer.toString(line.qty()));
            element(sb, 3, "Price", line.price().setScale(2, RoundingMode.HALF_UP).toPlainString());
            element(sb, 3, "FulfillType", line.fulfillType());
            if (line.alteration() != null) {
                indent(sb, 3).append("<Alteration>\n");
                element(sb, 4, "Type", line.alteration().type());
                if (line.alteration().measurementInches() != null) {
                    element(sb, 4, "MeasurementInches", line.alteration().measurementInches().toPlainString());
                }
                if (line.alteration().tailorShopNbr() != null) {
                    element(sb, 4, "TailorShopNbr", line.alteration().tailorShopNbr());
                }
                indent(sb, 3).append("</Alteration>\n");
            }
            indent(sb, 2).append("</Line>\n");
        }
        indent(sb, 1).append("</Lines>\n");
        sb.append("</Order>\n");
        return sb.toString();
    }

    /** {@code <OrderCancel>} notice published on TB.ORDERS.CANCEL. */
    public static String cancelXml(String orderNbr, String storeNbr, LocalDate cancelDate, String reason) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(XML_DECL);
        sb.append("<OrderCancel xmlns=\"").append(NAMESPACE).append("\">\n");
        element(sb, 1, "OrderNbr", orderNbr);
        element(sb, 1, "StoreNbr", storeNbr);
        element(sb, 1, "CancelDate", cancelDate.toString());
        if (reason != null && !reason.isBlank()) {
            element(sb, 1, "Reason", reason);
        }
        sb.append("</OrderCancel>\n");
        return sb.toString();
    }

    private static void element(StringBuilder sb, int depth, String name, String value) {
        indent(sb, depth).append('<').append(name).append('>')
                .append(escape(value))
                .append("</").append(name).append(">\n");
    }

    private static StringBuilder indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb;
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
