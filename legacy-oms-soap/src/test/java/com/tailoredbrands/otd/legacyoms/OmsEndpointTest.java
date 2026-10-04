package com.tailoredbrands.otd.legacyoms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.xml.transform.StringSource;

import javax.xml.transform.Source;
import java.util.Map;

import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.ResponseMatchers.clientOrSenderFault;
import static org.springframework.ws.test.server.ResponseMatchers.noFault;
import static org.springframework.ws.test.server.ResponseMatchers.xpath;

/**
 * Server-side SOAP tests through the Spring-WS dispatcher (no HTTP) using {@link MockWebServiceClient}.
 */
@SpringBootTest
class OmsEndpointTest {

    private static final String NS = "http://tailoredbrands.com/legacy/oms/v1";
    private static final Map<String, String> NAMESPACES = Map.of("oms", NS);

    @Autowired
    private ApplicationContext applicationContext;

    private MockWebServiceClient client;

    @BeforeEach
    void setUp() {
        client = MockWebServiceClient.createClient(applicationContext);
    }

    @Test
    void submitOrderThenGetStatus() {
        String orderNbr = "0875-261003-100001";
        Source submit = new StringSource(
                "<SubmitOrderRequest xmlns=\"" + NS + "\">"
                        + order(orderNbr, "T", "0875")
                        + "</SubmitOrderRequest>");

        client.sendRequest(withPayload(submit))
                .andExpect(noFault())
                .andExpect(xpath("/oms:SubmitOrderResponse/oms:OrderNbr", NAMESPACES).evaluatesTo(orderNbr))
                .andExpect(xpath("/oms:SubmitOrderResponse/oms:Status", NAMESPACES).evaluatesTo("IN_ALTERATION"))
                .andExpect(xpath("/oms:SubmitOrderResponse/oms:Message", NAMESPACES).evaluatesTo("ACCEPTED"));

        Source status = new StringSource(
                "<GetOrderStatusRequest xmlns=\"" + NS + "\"><OrderNbr>" + orderNbr + "</OrderNbr></GetOrderStatusRequest>");

        client.sendRequest(withPayload(status))
                .andExpect(noFault())
                .andExpect(xpath("/oms:GetOrderStatusResponse/oms:Status", NAMESPACES).evaluatesTo("IN_ALTERATION"))
                .andExpect(xpath("/oms:GetOrderStatusResponse/oms:Order/oms:StoreNbr", NAMESPACES).evaluatesTo("0875"))
                .andExpect(xpath("count(/oms:GetOrderStatusResponse/oms:Order/oms:Lines/oms:Line)", NAMESPACES)
                        .evaluatesTo(2));
    }

    @Test
    void resubmittingSameOrderNbrIsIdempotent() {
        String orderNbr = "1021-261003-100002";
        Source submit = new StringSource(
                "<SubmitOrderRequest xmlns=\"" + NS + "\">" + order(orderNbr, "R", "1021") + "</SubmitOrderRequest>");

        client.sendRequest(withPayload(submit))
                .andExpect(noFault())
                .andExpect(xpath("/oms:SubmitOrderResponse/oms:Message", NAMESPACES).evaluatesTo("ACCEPTED"));
        client.sendRequest(withPayload(submit))
                .andExpect(noFault())
                .andExpect(xpath("/oms:SubmitOrderResponse/oms:OrderNbr", NAMESPACES).evaluatesTo(orderNbr))
                .andExpect(xpath("/oms:SubmitOrderResponse/oms:Message", NAMESPACES)
                        .evaluatesTo("DUPLICATE - order already on file"));
    }

    @Test
    void unknownOrderReturnsClientFaultWithDetail() {
        Source status = new StringSource(
                "<GetOrderStatusRequest xmlns=\"" + NS + "\"><OrderNbr>NOPE-000</OrderNbr></GetOrderStatusRequest>");

        client.sendRequest(withPayload(status))
                .andExpect(clientOrSenderFault());
    }

    @Test
    void schemaInvalidRequestIsRejectedBeforeReachingTheEndpoint() {
        // StoreNbr "41" violates the [0-9]{4} pattern -> PayloadValidatingInterceptor returns a Client fault
        Source submit = new StringSource(
                "<SubmitOrderRequest xmlns=\"" + NS + "\">" + order("BAD-1", "R", "41") + "</SubmitOrderRequest>");

        client.sendRequest(withPayload(submit))
                .andExpect(clientOrSenderFault());
    }

    @Test
    void exportOrdersReturnsSeededOrdersForTheDateRange() {
        Source export = new StringSource(
                "<ExportOrdersRequest xmlns=\"" + NS + "\">"
                        + "<FromDate>2026-10-01</FromDate><ToDate>2026-10-01</ToDate>"
                        + "</ExportOrdersRequest>");

        client.sendRequest(withPayload(export))
                .andExpect(noFault())
                .andExpect(xpath("count(/oms:ExportOrdersResponse/oms:Orders/oms:Order)", NAMESPACES).evaluatesTo(9))
                .andExpect(xpath("/oms:ExportOrdersResponse/oms:Orders/@Count", NAMESPACES).evaluatesTo(9));
    }

    private static String order(String orderNbr, String type, String store) {
        return "<Order>"
                + "<OrderNbr>" + orderNbr + "</OrderNbr>"
                + "<OrderType>" + type + "</OrderType>"
                + "<StoreNbr>" + store + "</StoreNbr>"
                + "<CustNbr>C-10001</CustNbr>"
                + "<OrderDate>2026-10-03</OrderDate>"
                + "<Lines>"
                + "<Line><LineNbr>1</LineNbr><SKU>MW-SUIT-NAVY-42R</SKU><Qty>1</Qty><Price>599.99</Price><FulfillType>P</FulfillType></Line>"
                + "<Line><LineNbr>2</LineNbr><SKU>ALT-HEM-TROUSER</SKU><Qty>1</Qty><Price>25.00</Price><FulfillType>A</FulfillType>"
                + "<Alteration><Type>HEM</Type><MeasurementInches>31.5</MeasurementInches><TailorShopNbr>TS-EASTBAY</TailorShopNbr></Alteration>"
                + "</Line>"
                + "</Lines>"
                + "</Order>";
    }
}
