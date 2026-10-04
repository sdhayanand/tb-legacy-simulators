package com.tailoredbrands.otd.emsbroker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code simulator.*} settings (bound from env vars such as {@code PUBLISH_RATE_PER_MIN} in application.yml).
 */
@ConfigurationProperties(prefix = "simulator")
public class SimulatorProperties {

    /** Orders published per minute by the scheduled BW-style publisher; 0 disables scheduled publishing. */
    private int publishRatePerMin = 6;

    /** Destination for new orders. */
    private String ordersDestination = "TB.ORDERS.OUT";

    /** Destination for cancellations. */
    private String cancelDestination = "TB.ORDERS.CANCEL";

    /** Every message is copied here (EMS "audit" topic/queue the legacy ops team tails). */
    private String auditDestination = "TB.ORDERS.AUDIT";

    /** Acceptor the external bridges connect to. */
    private String acceptorUrl = "tcp://0.0.0.0:61616";

    /** How many recently published orders to remember (for /simulate/cancel/{orderNbr}). */
    private int rememberOrders = 1000;

    /** Optional seed for deterministic generation (0 = random). */
    private long seed = 0L;

    public int getPublishRatePerMin() {
        return publishRatePerMin;
    }

    public void setPublishRatePerMin(int publishRatePerMin) {
        this.publishRatePerMin = publishRatePerMin;
    }

    public String getOrdersDestination() {
        return ordersDestination;
    }

    public void setOrdersDestination(String ordersDestination) {
        this.ordersDestination = ordersDestination;
    }

    public String getCancelDestination() {
        return cancelDestination;
    }

    public void setCancelDestination(String cancelDestination) {
        this.cancelDestination = cancelDestination;
    }

    public String getAuditDestination() {
        return auditDestination;
    }

    public void setAuditDestination(String auditDestination) {
        this.auditDestination = auditDestination;
    }

    public String getAcceptorUrl() {
        return acceptorUrl;
    }

    public void setAcceptorUrl(String acceptorUrl) {
        this.acceptorUrl = acceptorUrl;
    }

    public int getRememberOrders() {
        return rememberOrders;
    }

    public void setRememberOrders(int rememberOrders) {
        this.rememberOrders = rememberOrders;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }
}
