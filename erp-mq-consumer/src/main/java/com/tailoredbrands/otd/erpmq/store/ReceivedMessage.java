package com.tailoredbrands.otd.erpmq.store;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One message taken off {@code ERP.ORDERS.IN}, with the parsed order summary the ERP would post to its ledger.
 *
 * @param format {@code LEGACY_XML} (TIBCO BW {@code <Order>}), {@code CANONICAL_JSON} (bridged OrderEvent) or {@code UNKNOWN}
 */
public record ReceivedMessage(
        String messageId,
        String correlationId,
        Instant receivedAt,
        String format,
        String eventType,
        String orderNbr,
        String orderType,
        String storeNbr,
        String custNbr,
        String orderDate,
        int lineCount,
        BigDecimal totalAmount,
        List<String> skus,
        String payload) {
}
