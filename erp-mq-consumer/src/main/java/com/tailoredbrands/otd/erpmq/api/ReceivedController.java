package com.tailoredbrands.otd.erpmq.api;

import com.tailoredbrands.otd.erpmq.config.ErpJmsProperties;
import com.tailoredbrands.otd.erpmq.store.ReceivedMessage;
import com.tailoredbrands.otd.erpmq.store.ReceivedMessageStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the ERP has received:
 * <ul>
 *   <li>{@code GET /received?limit=100&full=false} - newest first (payload omitted unless {@code full=true})</li>
 *   <li>{@code GET /received/{orderNbr}} - one message incl. payload</li>
 *   <li>{@code GET /received/stats} - totals and counts per store / order type / format</li>
 *   <li>{@code DELETE /received} - clear</li>
 * </ul>
 */
@RestController
public class ReceivedController {

    private final ReceivedMessageStore store;
    private final ErpJmsProperties properties;

    public ReceivedController(ReceivedMessageStore store, ErpJmsProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @GetMapping(value = "/received", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ReceivedMessage> received(@RequestParam(name = "limit", defaultValue = "100") int limit,
                                          @RequestParam(name = "full", defaultValue = "false") boolean full) {
        List<ReceivedMessage> recent = store.recent(Math.max(1, Math.min(limit, store.capacity())));
        if (full) {
            return recent;
        }
        return recent.stream().map(ReceivedController::withoutPayload).toList();
    }

    @GetMapping(value = "/received/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", properties.getProvider());
        body.put("queue", properties.getQueue());
        body.put("totalReceived", store.total());
        body.put("retained", store.size());
        body.put("capacity", store.capacity());
        body.put("duplicatesDetected", store.duplicates());
        body.put("perStore", store.countsPerStore());
        body.put("perOrderType", store.countsPerOrderType());
        body.put("perFormat", store.countsPerFormat());
        return body;
    }

    @GetMapping(value = "/received/{orderNbr}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReceivedMessage> byOrderNbr(@PathVariable("orderNbr") String orderNbr) {
        return store.byOrderNbr(orderNbr)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping(value = "/received", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> clear() {
        int before = store.size();
        store.clear();
        return Map.of("cleared", before);
    }

    private static ReceivedMessage withoutPayload(ReceivedMessage m) {
        return new ReceivedMessage(m.messageId(), m.correlationId(), m.receivedAt(), m.format(), m.eventType(),
                m.orderNbr(), m.orderType(), m.storeNbr(), m.custNbr(), m.orderDate(), m.lineCount(),
                m.totalAmount(), m.skus(), null);
    }
}
