package com.tailoredbrands.otd.legacyoms.service;

public class OrderNotFoundException extends OmsException {

    public OrderNotFoundException(String orderNbr) {
        super("ORDER_NOT_FOUND", "Order " + orderNbr + " is not on file", orderNbr);
    }
}
