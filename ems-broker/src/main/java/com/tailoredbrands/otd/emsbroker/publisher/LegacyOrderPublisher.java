package com.tailoredbrands.otd.emsbroker.publisher;

import com.tailoredbrands.otd.emsbroker.catalog.Catalog;
import com.tailoredbrands.otd.emsbroker.config.SimulatorProperties;
import com.tailoredbrands.otd.emsbroker.generator.LegacyOrderGenerator;
import com.tailoredbrands.otd.emsbroker.model.LegacyOrder;
import com.tailoredbrands.otd.emsbroker.xml.LegacyOrderXmlWriter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * Simulates the TIBCO BusinessWorks "POS order publisher" process: builds a legacy XML order and publishes it as a
 * JMS {@code TextMessage} on {@code TB.ORDERS.OUT} with the header properties the real BW process sets
 * ({@code storeId}, {@code eventType}, {@code orderType}, {@code JMSCorrelationID}), plus a copy on
 * {@code TB.ORDERS.AUDIT}.
 *
 * <p>Runs on a schedule ({@code PUBLISH_RATE_PER_MIN}, 0 disables) and on demand via {@code POST /simulate/orders}.
 */
@Component
public class LegacyOrderPublisher {

    private static final Logger log = LoggerFactory.getLogger(LegacyOrderPublisher.class);

    public static final String EVENT_ORDER_CREATED = "ORDER_CREATED";
    public static final String EVENT_ORDER_CANCELLED = "ORDER_CANCELLED";

    private final JmsTemplate jmsTemplate;
    private final SimulatorProperties properties;
    private final TaskScheduler scheduler;
    private final LegacyOrderGenerator generator;
    private final PublishStats stats = new PublishStats();
    private final Map<String, LegacyOrder> recentOrders;

    private ScheduledFuture<?> scheduledPublish;

    public LegacyOrderPublisher(JmsTemplate jmsTemplate, SimulatorProperties properties, TaskScheduler scheduler,
                                Catalog catalog) {
        this.jmsTemplate = jmsTemplate;
        this.properties = properties;
        this.scheduler = scheduler;
        this.generator = properties.getSeed() == 0L
                ? new LegacyOrderGenerator(catalog)
                : new LegacyOrderGenerator(catalog, properties.getSeed());
        int remember = Math.max(10, properties.getRememberOrders());
        this.recentOrders = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LegacyOrder> eldest) {
                return size() > remember;
            }
        });
    }

    @PostConstruct
    void startSchedule() {
        int rate = properties.getPublishRatePerMin();
        if (rate <= 0) {
            log.info("Scheduled publishing disabled (PUBLISH_RATE_PER_MIN={})", rate);
            return;
        }
        Duration period = Duration.ofMillis(Math.max(100L, 60_000L / rate));
        scheduledPublish = scheduler.scheduleAtFixedRate(this::publishScheduled, period);
        log.info("Publishing a legacy order every {} ms ({} / min) to {}", period.toMillis(), rate,
                properties.getOrdersDestination());
    }

    @PreDestroy
    void stopSchedule() {
        if (scheduledPublish != null) {
            scheduledPublish.cancel(false);
        }
    }

    private void publishScheduled() {
        try {
            publishOrder(generator.next());
        } catch (RuntimeException e) {
            stats.recordError();
            log.error("Scheduled publish failed: {}", e.getMessage(), e);
        }
    }

    /** Publish {@code count} freshly generated orders now; returns their order numbers. */
    public List<String> publishOrders(int count) {
        return publishOrders(count, null, null);
    }

    public List<String> publishOrders(int count, String orderType, String storeNbr) {
        List<String> orderNbrs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String type = orderType == null ? generator.randomType() : orderType;
            LegacyOrder order = generator.next(type, storeNbr);
            publishOrder(order);
            orderNbrs.add(order.orderNbr());
        }
        return orderNbrs;
    }

    public void publishOrder(LegacyOrder order) {
        String xml = LegacyOrderXmlWriter.toXml(order);
        String correlationId = order.correlationId();
        MDC.put("correlationId", correlationId);
        MDC.put("orderId", order.orderNbr());
        try {
            send(properties.getOrdersDestination(), xml, order, EVENT_ORDER_CREATED, correlationId);
            send(properties.getAuditDestination(), xml, order, EVENT_ORDER_CREATED, correlationId);
            stats.recordOrder(order.orderNbr(), order.orderType(), order.storeNbr());
            recentOrders.put(order.orderNbr(), order);
            log.info("Published legacy order {} type={} store={} lines={} total={}", order.orderNbr(),
                    order.orderType(), order.storeNbr(), order.lines().size(), order.total());
        } finally {
            MDC.remove("correlationId");
            MDC.remove("orderId");
        }
    }

    /** Publish an {@code <OrderCancel>} for a previously published order (or any order when storeNbr is given). */
    public Optional<String> publishCancel(String orderNbr, String storeNbrOverride, String reason) {
        LegacyOrder known = recentOrders.get(orderNbr);
        String storeNbr = known != null ? known.storeNbr() : storeNbrOverride;
        if (storeNbr == null) {
            return Optional.empty();
        }
        String xml = LegacyOrderXmlWriter.cancelXml(orderNbr, storeNbr, LocalDate.now(),
                reason == null ? "CUSTOMER_REQUEST" : reason);
        String correlationId = known != null ? known.correlationId() : "store-" + storeNbr + "-cancel-" + orderNbr;
        String orderType = known != null ? known.orderType() : "R";
        LegacyOrder forHeaders = known != null ? known
                : new LegacyOrder(orderNbr, orderType, storeNbr, null, LocalDate.now(), null, "CANCELLED", List.of());

        send(properties.getCancelDestination(), xml, forHeaders, EVENT_ORDER_CANCELLED, correlationId);
        send(properties.getAuditDestination(), xml, forHeaders, EVENT_ORDER_CANCELLED, correlationId);
        log.info("Published cancel for {} store={}", orderNbr, storeNbr);
        return Optional.of(xml);
    }

    private void send(String destination, String xml, LegacyOrder order, String eventType, String correlationId) {
        jmsTemplate.send(destination, session -> {
            TextMessage message = session.createTextMessage(xml);
            message.setJMSCorrelationID(correlationId);
            message.setStringProperty("storeId", order.storeNbr());
            message.setStringProperty("eventType", eventType);
            message.setStringProperty("orderType", order.orderType());
            message.setStringProperty("orderNbr", order.orderNbr());
            message.setStringProperty("messageId", UUID.randomUUID().toString());
            message.setStringProperty("source", "TIBCO_BW");
            message.setStringProperty("schemaVersion", "1");
            message.setJMSType("LegacyOrder");
            return message;
        });
        stats.recordPublished(destination);
    }

    public PublishStats stats() {
        return stats;
    }

    public Optional<LegacyOrder> recentOrder(String orderNbr) {
        return Optional.ofNullable(recentOrders.get(orderNbr));
    }

    public int effectiveRatePerMin() {
        return properties.getPublishRatePerMin();
    }
}
