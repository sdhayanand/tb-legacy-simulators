package com.tailoredbrands.otd.erpmq;

import com.tailoredbrands.otd.erpmq.store.ReceivedMessage;
import com.tailoredbrands.otd.erpmq.store.ReceivedMessageStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code JMS_PROVIDER=artemis}: the consumer connects to a real Artemis broker (Testcontainers) exactly as it
 * connects to the ems-broker simulator in docker-compose, and sees messages put on ERP.ORDERS.IN.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"erp.jms.provider=artemis", "erp.jms.queue=ERP.ORDERS.IN"})
class ArtemisConsumerIT {

    @Container
    static final GenericContainer<?> ARTEMIS = new GenericContainer<>("apache/activemq-artemis:latest-alpine")
            .withEnv("ANONYMOUS_LOGIN", "true")
            .withExposedPorts(61616)
            .waitingFor(Wait.forLogMessage(".*AMQ221007.*\\s", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void artemisProperties(DynamicPropertyRegistry registry) {
        registry.add("erp.jms.artemis.url",
                () -> "tcp://" + ARTEMIS.getHost() + ":" + ARTEMIS.getMappedPort(61616));
    }

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private ReceivedMessageStore store;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void consumesLegacyXmlFromErpOrdersIn() throws Exception {
        store.clear();
        String orderNbr = "0875-261003-000777";
        String xml = OrderMessageParserTest.LEGACY_XML
                .replace("0412-261003-000871", orderNbr)
                .replace("<StoreNbr>0412</StoreNbr>", "<StoreNbr>0875</StoreNbr>");

        jmsTemplate.send("ERP.ORDERS.IN", session -> {
            var message = session.createTextMessage(xml);
            message.setJMSCorrelationID("store-0875-txn-000777");
            message.setStringProperty("storeId", "0875");
            message.setStringProperty("eventType", "ORDER_CREATED");
            return message;
        });

        ReceivedMessage received = TestSupport.await(() -> store.byOrderNbr(orderNbr).orElse(null), Duration.ofSeconds(20));
        assertThat(received).isNotNull();
        assertThat(received.format()).isEqualTo("LEGACY_XML");
        assertThat(received.storeNbr()).isEqualTo("0875");
        assertThat(received.correlationId()).isEqualTo("store-0875-txn-000777");
        assertThat(received.messageId()).isNotBlank();
        assertThat(received.lineCount()).isEqualTo(2);

        ResponseEntity<Map> byNbr = rest.getForEntity("/received/" + orderNbr, Map.class);
        assertThat(byNbr.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(byNbr.getBody()).isNotNull();
        assertThat(String.valueOf(byNbr.getBody().get("payload"))).contains(orderNbr);
        assertThat(byNbr.getBody()).containsEntry("storeNbr", "0875");

        ResponseEntity<List> list = rest.getForEntity("/received?limit=10", List.class);
        assertThat(list.getBody()).isNotEmpty();

        ResponseEntity<Map> stats = rest.getForEntity("/received/stats", Map.class);
        assertThat(stats.getBody()).containsEntry("provider", "artemis");
        @SuppressWarnings("unchecked")
        Map<String, Object> perStore = (Map<String, Object>) stats.getBody().get("perStore");
        assertThat(perStore).containsKey("0875");

        rest.delete("/received");
        assertThat(store.size()).isZero();
    }

    @Test
    void consumesBytesMessagesAsNativeMqApplicationsPutThem() throws Exception {
        store.clear();
        String orderNbr = "1021-261003-000888";
        byte[] xml = OrderMessageParserTest.LEGACY_XML.replace("0412-261003-000871", orderNbr).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        jmsTemplate.send("ERP.ORDERS.IN", session -> {
            var message = session.createBytesMessage();
            message.writeBytes(xml);
            return message;
        });

        ReceivedMessage received = TestSupport.await(() -> store.byOrderNbr(orderNbr).orElse(null), Duration.ofSeconds(20));
        assertThat(received).isNotNull();
        assertThat(received.orderType()).isEqualTo("T");
    }
}
