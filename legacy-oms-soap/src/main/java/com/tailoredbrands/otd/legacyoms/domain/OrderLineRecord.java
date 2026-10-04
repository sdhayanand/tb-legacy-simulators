package com.tailoredbrands.otd.legacyoms.domain;

import java.math.BigDecimal;

/**
 * One row of {@code ORD_LINE}. Alteration columns are null unless {@code fulfillType == "A"}.
 */
public record OrderLineRecord(
        int lineNbr,
        String sku,
        int qty,
        BigDecimal price,
        String fulfillType,
        String altType,
        BigDecimal altMeasurementInches,
        String tailorShopNbr) {

    public boolean hasAlteration() {
        return altType != null && !altType.isBlank();
    }

    public BigDecimal extendedPrice() {
        return price.multiply(BigDecimal.valueOf(qty));
    }
}
