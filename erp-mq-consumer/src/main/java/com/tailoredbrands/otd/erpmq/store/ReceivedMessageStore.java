package com.tailoredbrands.otd.erpmq.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded in-memory ring of the most recent messages plus running counters (total, per store, per type).
 * Thread-safe; the listener container may run several consumer threads.
 */
public class ReceivedMessageStore {

    private final int capacity;
    private final Deque<ReceivedMessage> messages;
    private final Map<String, AtomicLong> perStore = new TreeMap<>();
    private final Map<String, AtomicLong> perOrderType = new TreeMap<>();
    private final Map<String, AtomicLong> perFormat = new TreeMap<>();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();

    public ReceivedMessageStore(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.messages = new ArrayDeque<>(this.capacity);
    }

    public synchronized void add(ReceivedMessage message) {
        if (message.orderNbr() != null && messages.stream().anyMatch(m -> message.orderNbr().equals(m.orderNbr())
                && message.eventType().equals(m.eventType()))) {
            duplicates.incrementAndGet();
        }
        messages.addFirst(message);
        while (messages.size() > capacity) {
            messages.removeLast();
        }
        total.incrementAndGet();
        perStore.computeIfAbsent(nullSafe(message.storeNbr()), k -> new AtomicLong()).incrementAndGet();
        perOrderType.computeIfAbsent(nullSafe(message.orderType()), k -> new AtomicLong()).incrementAndGet();
        perFormat.computeIfAbsent(nullSafe(message.format()), k -> new AtomicLong()).incrementAndGet();
    }

    /** Newest first. */
    public synchronized List<ReceivedMessage> recent(int limit) {
        List<ReceivedMessage> result = new ArrayList<>(Math.min(limit, messages.size()));
        Iterator<ReceivedMessage> it = messages.iterator();
        while (it.hasNext() && result.size() < limit) {
            result.add(it.next());
        }
        return result;
    }

    public synchronized Optional<ReceivedMessage> byOrderNbr(String orderNbr) {
        return messages.stream().filter(m -> orderNbr.equals(m.orderNbr())).findFirst();
    }

    public synchronized void clear() {
        messages.clear();
        perStore.clear();
        perOrderType.clear();
        perFormat.clear();
        total.set(0);
        duplicates.set(0);
    }

    public synchronized int size() {
        return messages.size();
    }

    public long total() {
        return total.get();
    }

    public long duplicates() {
        return duplicates.get();
    }

    public synchronized Map<String, Long> countsPerStore() {
        return snapshot(perStore);
    }

    public synchronized Map<String, Long> countsPerOrderType() {
        return snapshot(perOrderType);
    }

    public synchronized Map<String, Long> countsPerFormat() {
        return snapshot(perFormat);
    }

    public int capacity() {
        return capacity;
    }

    private static Map<String, Long> snapshot(Map<String, AtomicLong> source) {
        Map<String, Long> copy = new TreeMap<>();
        source.forEach((k, v) -> copy.put(k, v.get()));
        return copy;
    }

    private static String nullSafe(String value) {
        return value == null ? "UNKNOWN" : value;
    }
}
