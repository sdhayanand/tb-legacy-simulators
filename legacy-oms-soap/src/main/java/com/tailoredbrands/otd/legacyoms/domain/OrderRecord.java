package com.tailoredbrands.otd.legacyoms.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One row of {@code ORD_HDR} plus its {@code ORD_LINE} rows.
 */
public record OrderRecord(
        long ordId,
        String orderNbr,
        String orderType,
        String storeNbr,
        String custNbr,
        LocalDate orderDate,
        LocalDate eventDate,
        String status,
        BigDecimal totalAmt,
        Instant createdTs,
        Instant updatedTs,
        List<OrderLineRecord> lines) {

    public OrderRecord withLines(List<OrderLineRecord> newLines) {
        return new OrderRecord(ordId, orderNbr, orderType, storeNbr, custNbr, orderDate, eventDate, status,
                totalAmt, createdTs, updatedTs, newLines);
    }

    public static BigDecimal total(List<OrderLineRecord> lines) {
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderLineRecord line : lines) {
            sum = sum.add(line.extendedPrice());
        }
        return sum.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
