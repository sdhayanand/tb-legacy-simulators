package com.tailoredbrands.otd.emsbroker.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * A legacy store order exactly as TIBCO BusinessWorks would assemble it from the POS transaction before
 * publishing it on EMS. Mirrors the {@code Order} type of {@code oms.xsd}.
 *
 * @param orderNbr  POS order number, {@code SSSS-YYMMDD-NNNNNN}
 * @param orderType R / T / C / X / E
 * @param storeNbr  4-digit store number
 * @param custNbr   loyalty customer number (null for walk-ins)
 * @param orderDate business date
 * @param eventDate rental event date (X orders only)
 * @param status    legacy status code (NEW on creation)
 */
public record LegacyOrder(
        String orderNbr,
        String orderType,
        String storeNbr,
        String custNbr,
        LocalDate orderDate,
        LocalDate eventDate,
        String status,
        List<LegacyOrderLine> lines) {

    public record LegacyOrderLine(
            int lineNbr,
            String sku,
            int qty,
            BigDecimal price,
            String fulfillType,
            LegacyAlteration alteration) {
    }

    public record LegacyAlteration(String type, BigDecimal measurementInches, String tailorShopNbr) {
    }

    public BigDecimal total() {
        BigDecimal sum = BigDecimal.ZERO;
        for (LegacyOrderLine line : lines) {
            sum = sum.add(line.price().multiply(BigDecimal.valueOf(line.qty())));
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    public boolean hasAlteration() {
        return lines.stream().anyMatch(l -> l.alteration() != null);
    }

    /** Correlation id in the format the new platform uses ({@code store-0412-txn-889213}). */
    public String correlationId() {
        String txn = orderNbr.substring(orderNbr.lastIndexOf('-') + 1);
        return "store-" + storeNbr + "-txn-" + txn;
    }
}
