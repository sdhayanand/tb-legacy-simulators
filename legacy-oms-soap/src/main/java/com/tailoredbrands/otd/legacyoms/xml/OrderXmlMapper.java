package com.tailoredbrands.otd.legacyoms.xml;

import com.tailoredbrands.legacy.oms.xml.Alteration;
import com.tailoredbrands.legacy.oms.xml.FulfillTypeCode;
import com.tailoredbrands.legacy.oms.xml.Order;
import com.tailoredbrands.legacy.oms.xml.OrderLine;
import com.tailoredbrands.legacy.oms.xml.OrderTypeCode;
import com.tailoredbrands.legacy.oms.xml.Orders;
import com.tailoredbrands.legacy.oms.xml.StatusCode;
import com.tailoredbrands.otd.legacyoms.domain.OrderLineRecord;
import com.tailoredbrands.otd.legacyoms.domain.OrderRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps between the xjc-generated XML types and the JDBC records.
 */
@Component
public class OrderXmlMapper {

    /** XML → record. {@code ordId} and {@code orderNbr} are assigned by the caller (service). */
    public OrderRecord toRecord(Order xml, long ordId, String orderNbr, String status, Instant now) {
        List<OrderLineRecord> lines = new ArrayList<>();
        if (xml.getLines() != null) {
            for (OrderLine line : xml.getLines().getLine()) {
                Alteration alt = line.getAlteration();
                lines.add(new OrderLineRecord(
                        line.getLineNbr(),
                        line.getSKU(),
                        line.getQty(),
                        line.getPrice(),
                        line.getFulfillType() == null ? "P" : line.getFulfillType().value(),
                        alt == null ? null : alt.getType(),
                        alt == null ? null : alt.getMeasurementInches(),
                        alt == null ? null : alt.getTailorShopNbr()));
            }
        }
        return new OrderRecord(
                ordId,
                orderNbr,
                xml.getOrderType().value(),
                xml.getStoreNbr(),
                xml.getCustNbr(),
                XmlDates.toLocalDate(xml.getOrderDate()),
                XmlDates.toLocalDate(xml.getEventDate()),
                status,
                OrderRecord.total(lines),
                now,
                now,
                lines);
    }

    /** record → XML. */
    public Order toXml(OrderRecord record) {
        Order xml = new Order();
        xml.setOrderNbr(record.orderNbr());
        xml.setOrderType(OrderTypeCode.fromValue(record.orderType()));
        xml.setStoreNbr(record.storeNbr());
        xml.setCustNbr(record.custNbr());
        xml.setOrderDate(XmlDates.fromLocalDate(record.orderDate()));
        xml.setEventDate(XmlDates.fromLocalDate(record.eventDate()));
        xml.setStatus(toStatus(record.status()));

        Order.Lines lines = new Order.Lines();
        for (OrderLineRecord line : record.lines()) {
            OrderLine xl = new OrderLine();
            xl.setLineNbr(line.lineNbr());
            xl.setSKU(line.sku());
            xl.setQty(line.qty());
            xl.setPrice(scale(line.price()));
            xl.setFulfillType(FulfillTypeCode.fromValue(line.fulfillType()));
            if (line.hasAlteration()) {
                Alteration alt = new Alteration();
                alt.setType(line.altType());
                alt.setMeasurementInches(line.altMeasurementInches());
                alt.setTailorShopNbr(line.tailorShopNbr());
                xl.setAlteration(alt);
            }
            lines.getLine().add(xl);
        }
        xml.setLines(lines);
        return xml;
    }

    public Orders toXml(List<OrderRecord> records, LocalDate extractDate) {
        Orders orders = new Orders();
        for (OrderRecord record : records) {
            orders.getOrder().add(toXml(record));
        }
        orders.setExtractDate(XmlDates.fromLocalDate(extractDate));
        orders.setCount(records.size());
        return orders;
    }

    public static StatusCode toStatus(String status) {
        if (status == null || status.isBlank()) {
            return StatusCode.NEW;
        }
        return StatusCode.fromValue(status);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
