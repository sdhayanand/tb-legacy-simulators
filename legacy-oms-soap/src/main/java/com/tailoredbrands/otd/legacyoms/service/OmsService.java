package com.tailoredbrands.otd.legacyoms.service;

import com.tailoredbrands.legacy.oms.xml.GetOrderStatusResponse;
import com.tailoredbrands.legacy.oms.xml.Order;
import com.tailoredbrands.legacy.oms.xml.OrderLine;
import com.tailoredbrands.legacy.oms.xml.Orders;
import com.tailoredbrands.legacy.oms.xml.StatusCode;
import com.tailoredbrands.legacy.oms.xml.SubmitOrderResponse;
import com.tailoredbrands.otd.legacyoms.domain.OrderRecord;
import com.tailoredbrands.otd.legacyoms.repository.OrderRepository;
import com.tailoredbrands.otd.legacyoms.xml.OrderXmlMapper;
import com.tailoredbrands.otd.legacyoms.xml.XmlDates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Business logic of the legacy OMS: the same three operations the SOAP contract exposes.
 */
@Service
public class OmsService {

    private static final Logger log = LoggerFactory.getLogger(OmsService.class);

    private final OrderRepository repository;
    private final OrderXmlMapper mapper;
    private final Clock clock;

    public OmsService(OrderRepository repository, OrderXmlMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * SubmitOrder: idempotent on {@code OrderNbr}. Re-submitting an order that is already on file returns
     * the stored status with a DUPLICATE message instead of failing - exactly what the real OMS does when
     * TIBCO BW redelivers a message.
     */
    public SubmitOrderResponse submitOrder(Order xml) {
        if (xml == null) {
            throw new OrderValidationException("SubmitOrderRequest must contain an Order", null);
        }
        validate(xml);

        String requestedNbr = xml.getOrderNbr();
        if (requestedNbr != null && !requestedNbr.isBlank()) {
            Optional<OrderRecord> existing = repository.findByOrderNbr(requestedNbr.trim());
            if (existing.isPresent()) {
                log.info("SubmitOrder duplicate orderNbr={} status={}", requestedNbr, existing.get().status());
                return response(existing.get().orderNbr(), existing.get().status(),
                        "DUPLICATE - order already on file");
            }
        }

        long ordId = repository.nextOrderId();
        String orderNbr = (requestedNbr == null || requestedNbr.isBlank())
                ? String.format("OMS%09d", ordId)
                : requestedNbr.trim();
        String status = initialStatus(xml);
        Instant now = clock.instant();

        OrderRecord record = mapper.toRecord(xml, ordId, orderNbr, status, now);
        repository.insert(record);

        MDC.put("orderId", orderNbr);
        try {
            log.info("SubmitOrder accepted orderNbr={} type={} store={} lines={} total={}",
                    orderNbr, record.orderType(), record.storeNbr(), record.lines().size(), record.totalAmt());
        } finally {
            MDC.remove("orderId");
        }
        return response(orderNbr, status, "ACCEPTED");
    }

    public GetOrderStatusResponse getOrderStatus(String orderNbr) {
        OrderRecord record = repository.findByOrderNbr(orderNbr == null ? "" : orderNbr.trim())
                .orElseThrow(() -> new OrderNotFoundException(orderNbr));
        GetOrderStatusResponse response = new GetOrderStatusResponse();
        response.setOrderNbr(record.orderNbr());
        response.setStatus(OrderXmlMapper.toStatus(record.status()));
        response.setLastUpdated(XmlDates.fromInstant(record.updatedTs()));
        response.setOrder(mapper.toXml(record));
        return response;
    }

    public Orders exportOrders(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new OrderValidationException("FromDate and ToDate are required", null);
        }
        if (to.isBefore(from)) {
            throw new OrderValidationException("ToDate must not be before FromDate", null);
        }
        List<OrderRecord> records = repository.findByOrderDateBetween(from, to);
        return mapper.toXml(records, to);
    }

    public Orders exportOrders(LocalDate date) {
        return exportOrders(date, date);
    }

    public List<OrderRecord> listOrders(int limit) {
        return repository.findAll(limit);
    }

    public OrderRecord getOrder(String orderNbr) {
        return repository.findByOrderNbr(orderNbr).orElseThrow(() -> new OrderNotFoundException(orderNbr));
    }

    public OrderRecord updateStatus(String orderNbr, String status) {
        StatusCode code;
        try {
            code = StatusCode.fromValue(status);
        } catch (IllegalArgumentException e) {
            throw new OrderValidationException("Unknown status " + status, orderNbr);
        }
        if (repository.updateStatus(orderNbr, code.value()) == 0) {
            throw new OrderNotFoundException(orderNbr);
        }
        return getOrder(orderNbr);
    }

    private static void validate(Order xml) {
        String nbr = xml.getOrderNbr();
        if (xml.getOrderType() == null) {
            throw new OrderValidationException("OrderType is required", nbr);
        }
        if (xml.getStoreNbr() == null || !xml.getStoreNbr().matches("[0-9]{4}")) {
            throw new OrderValidationException("StoreNbr must be 4 digits", nbr);
        }
        if (xml.getOrderDate() == null) {
            throw new OrderValidationException("OrderDate is required", nbr);
        }
        if (xml.getLines() == null || xml.getLines().getLine().isEmpty()) {
            throw new OrderValidationException("At least one Line is required", nbr);
        }
        Set<Integer> lineNbrs = new HashSet<>();
        for (OrderLine line : xml.getLines().getLine()) {
            if (!lineNbrs.add(line.getLineNbr())) {
                throw new OrderValidationException("Duplicate LineNbr " + line.getLineNbr(), nbr);
            }
            if (line.getQty() <= 0) {
                throw new OrderValidationException("Qty must be positive on line " + line.getLineNbr(), nbr);
            }
            if (line.getPrice() == null || line.getPrice().signum() < 0) {
                throw new OrderValidationException("Price must be >= 0 on line " + line.getLineNbr(), nbr);
            }
            boolean alterationLine = line.getFulfillType() != null && "A".equals(line.getFulfillType().value());
            if (alterationLine && line.getAlteration() == null) {
                throw new OrderValidationException(
                        "FulfillType A requires an Alteration on line " + line.getLineNbr(), nbr);
            }
        }
        if ("X".equals(xml.getOrderType().value()) && xml.getEventDate() == null) {
            throw new OrderValidationException("Rental (X) orders require EventDate", nbr);
        }
    }

    /** Orders carrying an alteration work order start in IN_ALTERATION; everything else starts NEW. */
    private static String initialStatus(Order xml) {
        if (xml.getStatus() != null && xml.getStatus() == StatusCode.CANCELLED) {
            return StatusCode.CANCELLED.value();
        }
        boolean alteration = xml.getLines().getLine().stream().anyMatch(l -> l.getAlteration() != null);
        return alteration ? StatusCode.IN_ALTERATION.value() : StatusCode.NEW.value();
    }

    private static SubmitOrderResponse response(String orderNbr, String status, String message) {
        SubmitOrderResponse response = new SubmitOrderResponse();
        response.setOrderNbr(orderNbr);
        response.setStatus(OrderXmlMapper.toStatus(status));
        response.setMessage(message);
        return response;
    }
}
