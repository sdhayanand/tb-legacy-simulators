package com.tailoredbrands.otd.erpmq;

import com.tailoredbrands.otd.erpmq.consumer.OrderMessageParser;
import com.tailoredbrands.otd.erpmq.store.ReceivedMessage;
import com.tailoredbrands.otd.erpmq.store.ReceivedMessageStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMessageParserTest {

    static final String LEGACY_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Order xmlns="http://tailoredbrands.com/legacy/oms/v1">
              <OrderNbr>0412-261003-000871</OrderNbr>
              <OrderType>T</OrderType>
              <StoreNbr>0412</StoreNbr>
              <CustNbr>C-77812</CustNbr>
              <OrderDate>2026-10-03</OrderDate>
              <Status>NEW</Status>
              <Lines>
                <Line><LineNbr>1</LineNbr><SKU>MW-SUIT-NAVY-42R</SKU><Qty>1</Qty><Price>599.99</Price><FulfillType>P</FulfillType></Line>
                <Line><LineNbr>2</LineNbr><SKU>ALT-HEM-TROUSER</SKU><Qty>2</Qty><Price>25.00</Price><FulfillType>A</FulfillType>
                  <Alteration><Type>HEM</Type><MeasurementInches>31.5</MeasurementInches><TailorShopNbr>TS-EASTBAY</TailorShopNbr></Alteration>
                </Line>
              </Lines>
            </Order>
            """;

    static final String CANONICAL_JSON = """
            {"eventId":"6f1c0c8e-6f2a-4d8c-9a8e-4b6b0e2a9c11","eventType":"ORDER_CREATED",
             "eventTime":"2026-10-03T22:14:05.120Z","schemaVersion":"1","source":"ORDER_INTAKE_API",
             "correlationId":"store-0412-txn-889213",
             "order":{"orderId":"ORD-2026-000123","orderType":"TAILORED","channel":"STORE","storeId":"0412",
               "customerId":"C-77812","orderedAt":"2026-10-03T22:14:00Z","currency":"USD","totalAmount":649.99,
               "lines":[{"lineNumber":1,"sku":"MW-SUIT-NAVY-42R","quantity":1,"unitPrice":599.99,"fulfillmentType":"STORE_PICKUP"},
                        {"lineNumber":2,"sku":"ALT-HEM-TROUSER","quantity":1,"unitPrice":50.00,"fulfillmentType":"ALTERATION"}]}}
            """;

    private final OrderMessageParser parser = new OrderMessageParser();

    @Test
    void parsesLegacyXmlOrder() {
        ReceivedMessage m = parser.parse(LEGACY_XML, "ID:414d5120514d31", "store-0412-txn-000871", Instant.now());
        assertThat(m.format()).isEqualTo("LEGACY_XML");
        assertThat(m.eventType()).isEqualTo("ORDER_CREATED");
        assertThat(m.orderNbr()).isEqualTo("0412-261003-000871");
        assertThat(m.orderType()).isEqualTo("T");
        assertThat(m.storeNbr()).isEqualTo("0412");
        assertThat(m.custNbr()).isEqualTo("C-77812");
        assertThat(m.orderDate()).isEqualTo("2026-10-03");
        assertThat(m.lineCount()).isEqualTo(2);
        assertThat(m.skus()).containsExactly("MW-SUIT-NAVY-42R", "ALT-HEM-TROUSER");
        assertThat(m.totalAmount()).isEqualByComparingTo(new BigDecimal("649.99"));
        assertThat(m.payload()).isEqualTo(LEGACY_XML.strip());
    }

    @Test
    void parsesLegacyXmlCancel() {
        String cancel = "<OrderCancel xmlns=\"http://tailoredbrands.com/legacy/oms/v1\">"
                + "<OrderNbr>0412-261003-000871</OrderNbr><StoreNbr>0412</StoreNbr><CancelDate>2026-10-04</CancelDate>"
                + "<Reason>CUSTOMER_REQUEST</Reason></OrderCancel>";
        ReceivedMessage m = parser.parse(cancel, "ID:1", null, Instant.now());
        assertThat(m.eventType()).isEqualTo("ORDER_CANCELLED");
        assertThat(m.orderNbr()).isEqualTo("0412-261003-000871");
        assertThat(m.storeNbr()).isEqualTo("0412");
    }

    @Test
    void parsesCanonicalJsonEnvelope() {
        ReceivedMessage m = parser.parse(CANONICAL_JSON, "ID:2", null, Instant.now());
        assertThat(m.format()).isEqualTo("CANONICAL_JSON");
        assertThat(m.orderNbr()).isEqualTo("ORD-2026-000123");
        assertThat(m.orderType()).isEqualTo("T");
        assertThat(m.storeNbr()).isEqualTo("0412");
        assertThat(m.correlationId()).isEqualTo("store-0412-txn-889213");
        assertThat(m.lineCount()).isEqualTo(2);
        assertThat(m.totalAmount()).isEqualByComparingTo(new BigDecimal("649.99"));
    }

    @Test
    void keepsUnparseableMessagesAsUnknown() {
        ReceivedMessage m = parser.parse("hello ERP", "ID:3", null, Instant.now());
        assertThat(m.format()).isEqualTo("UNKNOWN");
        assertThat(m.payload()).isEqualTo("hello ERP");

        ReceivedMessage broken = parser.parse("<Order><unclosed>", "ID:4", null, Instant.now());
        assertThat(broken.format()).isEqualTo("UNKNOWN");
    }

    @Test
    void storeKeepsOnlyTheLastNAndCountsPerStore() {
        ReceivedMessageStore store = new ReceivedMessageStore(3);
        for (int i = 1; i <= 5; i++) {
            String xml = LEGACY_XML.replace("0412-261003-000871", "0412-261003-00000" + i);
            store.add(parser.parse(xml, "ID:" + i, null, Instant.now()));
        }
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.total()).isEqualTo(5);
        assertThat(store.recent(10)).extracting(ReceivedMessage::orderNbr)
                .containsExactly("0412-261003-000005", "0412-261003-000004", "0412-261003-000003");
        assertThat(store.countsPerStore()).containsEntry("0412", 5L);
        assertThat(store.countsPerOrderType()).containsEntry("T", 5L);
        assertThat(store.byOrderNbr("0412-261003-000004")).isPresent();
        assertThat(store.byOrderNbr("0412-261003-000001")).isEmpty();

        store.add(parser.parse(LEGACY_XML.replace("0412-261003-000871", "0412-261003-000005"), "ID:6", null, Instant.now()));
        assertThat(store.duplicates()).isEqualTo(1);

        store.clear();
        assertThat(store.size()).isZero();
        assertThat(store.total()).isZero();
    }
}
