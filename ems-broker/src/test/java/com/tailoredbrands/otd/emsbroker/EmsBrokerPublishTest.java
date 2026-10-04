package com.tailoredbrands.otd.emsbroker;

import com.tailoredbrands.otd.emsbroker.publisher.LegacyOrderPublisher;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.core.JmsTemplate;
import org.w3c.dom.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the embedded broker, publishes orders the way the BW process does and consumes them back
 * both in-VM (JmsTemplate) and over the TCP acceptor (as the jms-to-pubsub-bridge would).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "simulator.publish-rate-per-min=0",
                "simulator.acceptor-url=tcp://127.0.0.1:61699",
                "simulator.seed=2026"
        })
class EmsBrokerPublishTest {

    @Autowired
    private LegacyOrderPublisher publisher;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void publishesThreeOrdersWithTibcoStyleHeadersAndSchemaValidXml() throws Exception {
        drain("TB.ORDERS.OUT");
        drain("TB.ORDERS.AUDIT");

        List<String> published = publisher.publishOrders(3);
        assertThat(published).hasSize(3);

        List<String> received = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Message message = jmsTemplate.receive("TB.ORDERS.OUT");
            assertThat(message).as("message %d on TB.ORDERS.OUT", i).isNotNull().isInstanceOf(TextMessage.class);
            TextMessage text = (TextMessage) message;

            assertThat(text.getStringProperty("eventType")).isEqualTo("ORDER_CREATED");
            assertThat(text.getStringProperty("storeId")).matches("[0-9]{4}");
            assertThat(text.getStringProperty("orderType")).isIn("R", "T", "C", "X", "E");
            assertThat(text.getJMSCorrelationID()).startsWith("store-" + text.getStringProperty("storeId") + "-txn-");
            assertThat(text.getJMSMessageID()).isNotBlank();

            String xml = text.getText();
            XsdSupport.assertValid(xml);
            Document doc = XsdSupport.parse(xml);
            assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("Order");
            assertThat(XsdSupport.text(doc, "StoreNbr")).isEqualTo(text.getStringProperty("storeId"));
            assertThat(XsdSupport.text(doc, "OrderType")).isEqualTo(text.getStringProperty("orderType"));
            assertThat(doc.getElementsByTagNameNS(XsdSupport.NS, "Line").getLength()).isGreaterThanOrEqualTo(1);
            received.add(XsdSupport.text(doc, "OrderNbr"));
        }
        assertThat(received).containsExactlyInAnyOrderElementsOf(published);

        // every order is copied to the audit destination
        for (int i = 0; i < 3; i++) {
            assertThat(jmsTemplate.receive("TB.ORDERS.AUDIT")).as("audit copy %d", i).isNotNull();
        }
        assertThat(publisher.stats().published("TB.ORDERS.OUT")).isGreaterThanOrEqualTo(3);
        assertThat(publisher.stats().published("TB.ORDERS.AUDIT")).isGreaterThanOrEqualTo(3);
    }

    @Test
    void restApiPublishesAndCancelsOrdersAndReportsStats() throws Exception {
        drain("TB.ORDERS.OUT");
        drain("TB.ORDERS.CANCEL");

        ResponseEntity<Map> response = rest.postForEntity("/simulate/orders?count=2&type=T&store=0875", null, Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        List<String> orderNbrs = (List<String>) response.getBody().get("orderNbrs");
        assertThat(orderNbrs).hasSize(2).allMatch(n -> n.startsWith("0875-"));

        for (int i = 0; i < 2; i++) {
            TextMessage message = (TextMessage) jmsTemplate.receive("TB.ORDERS.OUT");
            assertThat(message).isNotNull();
            assertThat(message.getStringProperty("orderType")).isEqualTo("T");
            assertThat(message.getText()).contains("<Alteration>");
        }

        ResponseEntity<Map> cancel = rest.postForEntity("/simulate/cancel/" + orderNbrs.get(0), null, Map.class);
        assertThat(cancel.getStatusCode().is2xxSuccessful()).isTrue();
        TextMessage cancelMessage = (TextMessage) jmsTemplate.receive("TB.ORDERS.CANCEL");
        assertThat(cancelMessage).isNotNull();
        assertThat(cancelMessage.getStringProperty("eventType")).isEqualTo("ORDER_CANCELLED");
        assertThat(cancelMessage.getStringProperty("storeId")).isEqualTo("0875");
        XsdSupport.assertValid(cancelMessage.getText());
        assertThat(XsdSupport.parse(cancelMessage.getText()).getDocumentElement().getLocalName()).isEqualTo("OrderCancel");

        ResponseEntity<Map> unknown = rest.postForEntity("/simulate/cancel/NOPE-1", null, Map.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<Map> stats = rest.getForEntity("/stats", Map.class);
        assertThat(stats.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(stats.getBody()).containsKeys("published", "ordersPerType", "ordersPerStore", "queueDepths");
        @SuppressWarnings("unchecked")
        Map<String, Object> publishedCounts = (Map<String, Object>) stats.getBody().get("published");
        assertThat(publishedCounts).containsKey("TB.ORDERS.OUT").containsKey("TB.ORDERS.CANCEL");

        ResponseEntity<String> sample = rest.getForEntity("/samples/tailored", String.class);
        assertThat(sample.getBody()).contains(SampleXml.TAILORED_ORDER_NBR);
    }

    @Test
    void externalClientsCanConnectThroughTheTcpAcceptor() throws Exception {
        drain("TB.ORDERS.OUT");
        publisher.publishOrders(1);

        ActiveMQConnectionFactory external = new ActiveMQConnectionFactory("tcp://127.0.0.1:61699");
        try {
            JmsTemplate externalTemplate = new JmsTemplate(external);
            externalTemplate.setReceiveTimeout(5000L);
            Message message = externalTemplate.receive("TB.ORDERS.OUT");
            assertThat(message).isNotNull().isInstanceOf(TextMessage.class);
            assertThat(message.getStringProperty("eventType")).isEqualTo("ORDER_CREATED");
        } finally {
            external.close();
        }
    }

    private void drain(String destination) {
        long previous = jmsTemplate.getReceiveTimeout();
        jmsTemplate.setReceiveTimeout(200L);
        try {
            while (jmsTemplate.receive(destination) != null) {
                // discard
            }
        } finally {
            jmsTemplate.setReceiveTimeout(previous);
        }
    }
}
