package com.tailoredbrands.otd.legacyoms.service;

/**
 * Base class for business errors raised by the OMS; mapped to SOAP faults (with a {@code <Fault>} detail)
 * and to HTTP 4xx responses on the admin API.
 */
public class OmsException extends RuntimeException {

    private final String code;
    private final String orderNbr;

    public OmsException(String code, String message, String orderNbr) {
        super(message);
        this.code = code;
        this.orderNbr = orderNbr;
    }

    public String getCode() {
        return code;
    }

    public String getOrderNbr() {
        return orderNbr;
    }
}
