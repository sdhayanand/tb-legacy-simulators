package com.tailoredbrands.otd.emsbroker.publisher;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Published-message counters per destination (what {@code GET /stats} reports).
 */
public class PublishStats {

    private final ConcurrentHashMap<String, AtomicLong> perDestination = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> perOrderType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> perStore = new ConcurrentHashMap<>();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicReference<String> lastOrderNbr = new AtomicReference<>();
    private final AtomicReference<Instant> lastPublishedAt = new AtomicReference<>();
    private final Instant startedAt = Instant.now();

    public void recordPublished(String destination) {
        perDestination.computeIfAbsent(destination, d -> new AtomicLong()).incrementAndGet();
        lastPublishedAt.set(Instant.now());
    }

    public void recordOrder(String orderNbr, String orderType, String storeNbr) {
        perOrderType.computeIfAbsent(orderType, t -> new AtomicLong()).incrementAndGet();
        perStore.computeIfAbsent(storeNbr, s -> new AtomicLong()).incrementAndGet();
        lastOrderNbr.set(orderNbr);
    }

    public void recordError() {
        errors.incrementAndGet();
    }

    public long published(String destination) {
        AtomicLong counter = perDestination.get(destination);
        return counter == null ? 0L : counter.get();
    }

    public Map<String, Long> publishedPerDestination() {
        return snapshot(perDestination);
    }

    public Map<String, Long> ordersPerType() {
        return snapshot(perOrderType);
    }

    public Map<String, Long> ordersPerStore() {
        return snapshot(perStore);
    }

    public long errors() {
        return errors.get();
    }

    public String lastOrderNbr() {
        return lastOrderNbr.get();
    }

    public Instant lastPublishedAt() {
        return lastPublishedAt.get();
    }

    public Instant startedAt() {
        return startedAt;
    }

    private static Map<String, Long> snapshot(Map<String, AtomicLong> source) {
        Map<String, Long> copy = new TreeMap<>();
        source.forEach((k, v) -> copy.put(k, v.get()));
        return copy;
    }
}
