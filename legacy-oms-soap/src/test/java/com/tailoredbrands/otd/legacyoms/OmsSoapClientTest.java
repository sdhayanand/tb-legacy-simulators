package com.tailoredbrands.otd.legacyoms;

import com.tailoredbrands.legacy.oms.xml.FulfillTypeCode;
import com.tailoredbrands.legacy.oms.xml.GetOrderStatusRequest;
import com.tailoredbrands.legacy.oms.xml.GetOrderStatusResponse;
import com.tailoredbrands.legacy.oms.xml.Order;
import com.tailoredbrands.legacy.oms.xml.OrderLine;
import com.tailoredbrands.legacy.oms.xml.OrderTypeCode;
import com.tailoredbrands.legacy.oms.xml.StatusCode;
import com.tailoredbrands.legacy.oms.xml.SubmitOrderRequest;
import com.tailoredbrands.legacy.oms.xml.SubmitOrderResponse;
import com.tailoredbrands.otd.legacyoms.xml.XmlDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client-side test over real HTTP: a {@link WebServiceTemplate} with the xjc-generated JAXB classes talks to
 * the running SOAP endpoint, the way TIBCO BW's SOAP activity would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OmsSoapClientTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private WebServiceTemplate webServiceTemplate;

    @BeforeEach
    void setUp() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("com.tailoredbrands.legacy.oms.xml");
        webServiceTemplate = new WebServiceTemplate(marshaller);
        webServiceTemplate.setDefaultUri("http://localhost:" + port + "/ws");
    }

    @Test
    void wsdlIsPublished() {
        ResponseEntity<String> wsdl = rest.getForEntity("http://localhost:" + port + "/ws/oms.wsdl", String.class);
        assertThat(wsdl.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(wsdl.getBody())
                .contains("SubmitOrder")
                .contains("GetOrderStatus")
                .contains("ExportOrders")
                .contains("http://tailoredbrands.com/legacy/oms/v1")
                .contains("http://localhost:" + port + "/ws");
    }

    @Test
    void submitOrderOverHttpAndReadItBack() {
        String orderNbr = "0117-261003-200001";

        SubmitOrderRequest request = new SubmitOrderRequest();
        request.setOrder(sampleOrder(orderNbr));

        SubmitOrderResponse submitted = (SubmitOrderResponse) webServiceTemplate.marshalSendAndReceive(request);
        assertThat(submitted.getOrderNbr()).isEqualTo(orderNbr);
        assertThat(submitted.getStatus()).isEqualTo(StatusCode.NEW);
        assertThat(submitted.getMessage()).isEqualTo("ACCEPTED");

        GetOrderStatusRequest statusRequest = new GetOrderStatusRequest();
        statusRequest.setOrderNbr(orderNbr);
        GetOrderStatusResponse status = (GetOrderStatusResponse) webServiceTemplate.marshalSendAndReceive(statusRequest);

        assertThat(status.getOrderNbr()).isEqualTo(orderNbr);
        assertThat(status.getStatus()).isEqualTo(StatusCode.NEW);
        assertThat(status.getLastUpdated()).isNotNull();
        assertThat(status.getOrder()).isNotNull();
        assertThat(status.getOrder().getStoreNbr()).isEqualTo("0117");
        assertThat(status.getOrder().getOrderType()).isEqualTo(OrderTypeCode.R);
        assertThat(status.getOrder().getLines().getLine()).hasSize(1);
        assertThat(status.getOrder().getLines().getLine().get(0).getSKU()).isEqualTo("JAB-SUIT-NAVY-42R");
    }

    @Test
    void unknownOrderRaisesSoapFault() {
        GetOrderStatusRequest statusRequest = new GetOrderStatusRequest();
        statusRequest.setOrderNbr("DOES-NOT-EXIST");

        assertThatThrownBy(() -> webServiceTemplate.marshalSendAndReceive(statusRequest))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("DOES-NOT-EXIST");
    }

    private static Order sampleOrder(String orderNbr) {
        Order order = new Order();
        order.setOrderNbr(orderNbr);
        order.setOrderType(OrderTypeCode.R);
        order.setStoreNbr("0117");
        order.setCustNbr("C-20001");
        order.setOrderDate(XmlDates.fromLocalDate(LocalDate.of(2026, 10, 3)));

        OrderLine line = new OrderLine();
        line.setLineNbr(1);
        line.setSKU("JAB-SUIT-NAVY-42R");
        line.setQty(1);
        line.setPrice(new BigDecimal("695.00"));
        line.setFulfillType(FulfillTypeCode.P);

        Order.Lines lines = new Order.Lines();
        lines.getLine().add(line);
        order.setLines(lines);
        return order;
    }
}
