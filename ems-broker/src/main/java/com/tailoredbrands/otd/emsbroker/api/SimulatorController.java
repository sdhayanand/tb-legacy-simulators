package com.tailoredbrands.otd.emsbroker.api;

import com.tailoredbrands.otd.emsbroker.SampleXml;
import com.tailoredbrands.otd.emsbroker.config.SimulatorProperties;
import com.tailoredbrands.otd.emsbroker.model.LegacyOrder;
import com.tailoredbrands.otd.emsbroker.publisher.LegacyOrderPublisher;
import com.tailoredbrands.otd.emsbroker.publisher.PublishStats;
import com.tailoredbrands.otd.emsbroker.xml.LegacyOrderXmlWriter;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Simulation controls and statistics.
 *
 * <ul>
 *   <li>{@code POST /simulate/orders?count=n[&type=T][&store=0412]} - publish n orders now, returns their OrderNbrs</li>
 *   <li>{@code POST /simulate/cancel/{orderNbr}[?store=0412&reason=...]} - publish an OrderCancel</li>
 *   <li>{@code GET /simulate/orders/{orderNbr}} - the XML of a recently published order</li>
 *   <li>{@code GET /stats} - published counts per destination, per type, per store, queue depths</li>
 *   <li>{@code GET /samples/{retail|tailored|rental}} - the canned sample XML documents</li>
 * </ul>
 */
@RestController
public class SimulatorController {

    private static final Set<String> ORDER_TYPES = Set.of("R", "T", "C", "X", "E");

    private final LegacyOrderPublisher publisher;
    private final SimulatorProperties properties;
    private final ObjectProvider<EmbeddedActiveMQ> embeddedBroker;

    public SimulatorController(LegacyOrderPublisher publisher, SimulatorProperties properties,
                               ObjectProvider<EmbeddedActiveMQ> embeddedBroker) {
        this.publisher = publisher;
        this.properties = properties;
        this.embeddedBroker = embeddedBroker;
    }

    @PostMapping(value = "/simulate/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> simulateOrders(
            @RequestParam(name = "count", defaultValue = "1") int count,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "store", required = false) String store) {
        if (count < 1 || count > 10_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "count must be between 1 and 10000"));
        }
        String orderType = type == null ? null : type.trim().toUpperCase();
        if (orderType != null && !ORDER_TYPES.contains(orderType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "type must be one of " + ORDER_TYPES));
        }
        List<String> orderNbrs = publisher.publishOrders(count, orderType, store);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("published", orderNbrs.size());
        body.put("destination", properties.getOrdersDestination());
        body.put("orderNbrs", orderNbrs);
        return ResponseEntity.ok(body);
    }

    @PostMapping(value = "/simulate/cancel/{orderNbr}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> simulateCancel(
            @PathVariable("orderNbr") String orderNbr,
            @RequestParam(name = "store", required = false) String store,
            @RequestParam(name = "reason", required = false) String reason) {
        Optional<String> xml = publisher.publishCancel(orderNbr, store, reason);
        if (xml.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Order " + orderNbr + " was not published by this simulator; pass ?store=NNNN to cancel anyway"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderNbr", orderNbr);
        body.put("destination", properties.getCancelDestination());
        body.put("xml", xml.get());
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/simulate/orders/{orderNbr}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> recentOrder(@PathVariable("orderNbr") String orderNbr) {
        Optional<LegacyOrder> order = publisher.recentOrder(orderNbr);
        return order.map(o -> ResponseEntity.ok(LegacyOrderXmlWriter.toXml(o)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> stats() {
        PublishStats stats = publisher.stats();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startedAt", stats.startedAt().toString());
        body.put("publishRatePerMin", publisher.effectiveRatePerMin());
        body.put("acceptor", properties.getAcceptorUrl());
        body.put("published", stats.publishedPerDestination());
        body.put("ordersPerType", stats.ordersPerType());
        body.put("ordersPerStore", stats.ordersPerStore());
        body.put("errors", stats.errors());
        body.put("lastOrderNbr", stats.lastOrderNbr());
        body.put("lastPublishedAt", stats.lastPublishedAt() == null ? null : stats.lastPublishedAt().toString());
        body.put("queueDepths", queueDepths());
        return body;
    }

    @GetMapping(value = "/samples/{name}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sample(@PathVariable("name") String name) {
        return switch (name.toLowerCase()) {
            case "retail" -> ResponseEntity.ok(SampleXml.retail());
            case "tailored" -> ResponseEntity.ok(SampleXml.tailored());
            case "rental" -> ResponseEntity.ok(SampleXml.rental());
            default -> ResponseEntity.notFound().build();
        };
    }

    /** Current depth / messages-added for the simulated EMS destinations (best effort). */
    private Map<String, Object> queueDepths() {
        Map<String, Object> depths = new LinkedHashMap<>();
        EmbeddedActiveMQ embedded = embeddedBroker.getIfAvailable();
        if (embedded == null || embedded.getActiveMQServer() == null) {
            return depths;
        }
        ActiveMQServer server = embedded.getActiveMQServer();
        for (String name : List.of(properties.getOrdersDestination(), properties.getCancelDestination(),
                properties.getAuditDestination(), "ERP.ORDERS.IN")) {
            try {
                Queue queue = server.locateQueue(SimpleString.of(name));
                if (queue != null) {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("messageCount", queue.getMessageCount());
                    q.put("messagesAdded", queue.getMessagesAdded());
                    q.put("consumerCount", queue.getConsumerCount());
                    depths.put(name, q);
                }
            } catch (RuntimeException e) {
                depths.put(name, Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return depths;
    }
}
