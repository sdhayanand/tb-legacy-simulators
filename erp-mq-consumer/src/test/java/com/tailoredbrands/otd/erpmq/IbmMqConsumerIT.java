package com.tailoredbrands.otd.erpmq;

import com.tailoredbrands.otd.erpmq.store.ReceivedMessage;
import com.tailoredbrands.otd.erpmq.store.ReceivedMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real thing: IBM MQ Advanced for Developers in a container ({@code icr.io/ibm-messaging/mq}), queue manager
 * QM1, channel DEV.APP.SVRCONN, user {@code app}, queue DEV.QUEUE.1. Opt-in because the image is ~1 GB and takes a
 * minute to start: {@code RUN_MQ_IT=true mvn -pl erp-mq-consumer verify}.
 */
@EnabledIfEnvironmentVariable(named = "RUN_MQ_IT", matches = "true")
@Testcontainers
@SpringBootTest(properties = {
        "erp.jms.provider=ibmmq",
        "erp.jms.queue=DEV.QUEUE.1",
        "erp.jms.ibmmq.queue-manager=QM1",
        "erp.jms.ibmmq.channel=DEV.APP.SVRCONN",
        "erp.jms.ibmmq.user=app",
        "erp.jms.ibmmq.password=passw0rd"
})
class IbmMqConsumerIT {

    @Container
    static final GenericContainer<?> MQ = new GenericContainer<>("icr.io/ibm-messaging/mq:latest")
            .withEnv("LICENSE", "accept")
            .withEnv("MQ_QMGR_NAME", "QM1")
            .withEnv("MQ_APP_PASSWORD", "passw0rd")
            .withEnv("MQ_ADMIN_PASSWORD", "passw0rd")
            .withExposedPorts(1414, 9443)
            .waitingFor(Wait.forLogMessage(".*Started web server.*\\s", 1))
            .withStartupTimeout(Duration.ofMinutes(5));

    @DynamicPropertySource
    static void mqProperties(DynamicPropertyRegistry registry) {
        registry.add("erp.jms.ibmmq.host", MQ::getHost);
        registry.add("erp.jms.ibmmq.port", () -> MQ.getMappedPort(1414));
    }

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private ReceivedMessageStore store;

    @Test
    void consumesFromIbmMqDevQueue() throws Exception {
        store.clear();
        String orderNbr = "1104-261003-000999";
        String xml = OrderMessageParserTest.LEGACY_XML
                .replace("0412-261003-000871", orderNbr)
                .replace("<StoreNbr>0412</StoreNbr>", "<StoreNbr>1104</StoreNbr>");

        jmsTemplate.send("DEV.QUEUE.1", session -> {
            var message = session.createTextMessage(xml);
            message.setJMSCorrelationID("store-1104-txn-000999");
            return message;
        });

        ReceivedMessage received = TestSupport.await(() -> store.byOrderNbr(orderNbr).orElse(null), Duration.ofSeconds(30));
        assertThat(received).isNotNull();
        assertThat(received.storeNbr()).isEqualTo("1104");
        assertThat(received.messageId()).startsWith("ID:");
        assertThat(received.correlationId()).contains("store-1104-txn-000999");
    }
}
