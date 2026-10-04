package com.tailoredbrands.otd.legacyoms.service;

public class OrderValidationException extends OmsException {

    public OrderValidationException(String message, String orderNbr) {
        super("ORDER_INVALID", message, orderNbr);
    }
}
