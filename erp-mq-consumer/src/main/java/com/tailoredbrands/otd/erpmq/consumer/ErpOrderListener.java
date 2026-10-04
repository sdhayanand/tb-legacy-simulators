package com.tailoredbrands.otd.erpmq.consumer;

import com.tailoredbrands.otd.erpmq.store.ReceivedMessage;
import com.tailoredbrands.otd.erpmq.store.ReceivedMessageStore;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The ERP's inbound order interface: reads {@code ERP.ORDERS.IN}, accepts both JMS {@code TextMessage}s and the
 * {@code BytesMessage}s that native MQ applications (and some bridges) put, and records a summary.
 */
@Component
public class ErpOrderListener {

    private static final Logger log = LoggerFactory.getLogger(ErpOrderListener.class);

    private final ReceivedMessageStore store;
    private final OrderMessageParser parser;

    public ErpOrderListener(ReceivedMessageStore store, OrderMessageParser parser) {
        this.store = store;
        this.parser = parser;
    }

    @JmsListener(destination = "${erp.jms.queue}", containerFactory = "jmsListenerContainerFactory")
    public void onMessage(Message message) throws JMSException {
        String body = bodyOf(message);
        String messageId = message.getJMSMessageID();
        String correlationId = message.getJMSCorrelationID();
        ReceivedMessage received = parser.parse(body, messageId, correlationId, Instant.now());
        store.add(received);

        MDC.put("correlationId", correlationId == null ? "" : correlationId);
        MDC.put("orderId", received.orderNbr() == null ? "" : received.orderNbr());
        try {
            log.info("ERP received {} {} orderNbr={} type={} store={} lines={} total={} messageId={}",
                    received.format(), received.eventType(), received.orderNbr(), received.orderType(),
                    received.storeNbr(), received.lineCount(), received.totalAmount(), messageId);
        } finally {
            MDC.remove("correlationId");
            MDC.remove("orderId");
        }
    }

    static String bodyOf(Message message) throws JMSException {
        if (message instanceof TextMessage text) {
            return text.getText();
        }
        if (message instanceof BytesMessage bytes) {
            long length = bytes.getBodyLength();
            if (length > Integer.MAX_VALUE) {
                throw new JMSException("Message body too large: " + length);
            }
            byte[] buffer = new byte[(int) length];
            bytes.readBytes(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
        return String.valueOf(message.getBody(Object.class));
    }
}
