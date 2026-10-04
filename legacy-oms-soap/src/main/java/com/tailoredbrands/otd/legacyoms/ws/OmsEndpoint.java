package com.tailoredbrands.otd.legacyoms.ws;

import com.tailoredbrands.legacy.oms.xml.ExportOrdersRequest;
import com.tailoredbrands.legacy.oms.xml.ExportOrdersResponse;
import com.tailoredbrands.legacy.oms.xml.GetOrderStatusRequest;
import com.tailoredbrands.legacy.oms.xml.GetOrderStatusResponse;
import com.tailoredbrands.legacy.oms.xml.SubmitOrderRequest;
import com.tailoredbrands.legacy.oms.xml.SubmitOrderResponse;
import com.tailoredbrands.otd.legacyoms.service.OmsService;
import com.tailoredbrands.otd.legacyoms.xml.XmlDates;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/**
 * The three SOAP operations of the legacy OMS: SubmitOrder, GetOrderStatus, ExportOrders.
 */
@Endpoint
public class OmsEndpoint {

    private static final String NS = WebServiceConfig.NAMESPACE;

    private final OmsService service;

    public OmsEndpoint(OmsService service) {
        this.service = service;
    }

    @PayloadRoot(namespace = NS, localPart = "SubmitOrderRequest")
    @ResponsePayload
    public SubmitOrderResponse submitOrder(@RequestPayload SubmitOrderRequest request) {
        return service.submitOrder(request.getOrder());
    }

    @PayloadRoot(namespace = NS, localPart = "GetOrderStatusRequest")
    @ResponsePayload
    public GetOrderStatusResponse getOrderStatus(@RequestPayload GetOrderStatusRequest request) {
        return service.getOrderStatus(request.getOrderNbr());
    }

    @PayloadRoot(namespace = NS, localPart = "ExportOrdersRequest")
    @ResponsePayload
    public ExportOrdersResponse exportOrders(@RequestPayload ExportOrdersRequest request) {
        ExportOrdersResponse response = new ExportOrdersResponse();
        response.setOrders(service.exportOrders(
                XmlDates.toLocalDate(request.getFromDate()),
                XmlDates.toLocalDate(request.getToDate())));
        return response;
    }
}
