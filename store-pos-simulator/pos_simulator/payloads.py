"""Payload builders: canonical JSON for order-intake-api `POST /v1/orders` and a SOAP 1.1 envelope carrying the
legacy `SubmitOrderRequest` for `/ws/orders` (the order-intake-api SOAP adapter) or legacy-oms-soap `/ws`."""

from __future__ import annotations

from datetime import timedelta
from xml.sax.saxutils import escape

from .generator import CANONICAL_FULFILL, CANONICAL_TYPE, PosOrder

LEGACY_NS = "http://tailoredbrands.com/legacy/oms/v1"
SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"


def to_intake_json(order: PosOrder) -> dict:
    """The `order` object of the canonical OrderEvent (ARCHITECTURE.md section 3.1) - what a POS posts."""
    lines = []
    for line in order.lines:
        item: dict = {
            "lineNumber": line.line_nbr,
            "sku": line.sku,
            "quantity": line.qty,
            "unitPrice": float(line.price),
            "fulfillmentType": CANONICAL_FULFILL[line.fulfill_type],
        }
        if line.alteration is not None:
            item["alteration"] = {
                "type": line.alteration.type,
                "measurementInches": float(line.alteration.measurement_inches),
                "tailorShopId": line.alteration.tailor_shop_nbr,
            }
        lines.append(item)

    promised = order.order_date + timedelta(days=7 if order.has_alteration else 0)
    if order.order_type == "C":
        promised = order.order_date + timedelta(weeks=6)
    elif order.order_type == "E":
        promised = order.order_date + timedelta(days=5)

    payload: dict = {
        "orderId": order.order_nbr,
        "orderType": CANONICAL_TYPE[order.order_type],
        "channel": "WEB" if order.order_type == "E" else "STORE",  # canonical Channel enum: STORE, WEB, CALL_CENTER
        "storeId": order.store_nbr,
        "customerId": order.cust_nbr,
        "orderedAt": order.ordered_at.isoformat().replace("+00:00", "Z"),
        "promisedDate": promised.isoformat(),
        "currency": "USD",
        "totalAmount": float(order.total),
        "lines": lines,
        "correlationId": order.correlation_id,
        "rental": None,
        "shipTo": None,
    }
    if order.order_type == "X" and order.event_date is not None:
        payload["rental"] = {
            "eventId": f"EVT-{order.order_nbr}",
            "eventDate": order.event_date.isoformat(),
            "returnDueDate": (order.event_date + timedelta(days=2)).isoformat(),
            "groupId": f"G-{order.store_nbr}-{order.order_nbr[-6:]}",
        }
    if order.order_type in ("E", "C"):
        payload["shipTo"] = {
            "name": "Simulated Customer",
            "line1": "100 Main St",
            "city": "Pleasanton",
            "state": "CA",
            "postalCode": "94588",
            "country": "US",
        }
    return payload


def to_legacy_order_xml(order: PosOrder, indent: int = 0) -> str:
    """The legacy <Order> element (same shape as oms.xsd and the ems-broker messages)."""
    pad = "  " * indent
    out = [f'{pad}<Order xmlns="{LEGACY_NS}">']
    out.append(f"{pad}  <OrderNbr>{escape(order.order_nbr)}</OrderNbr>")
    out.append(f"{pad}  <OrderType>{order.order_type}</OrderType>")
    out.append(f"{pad}  <StoreNbr>{order.store_nbr}</StoreNbr>")
    if order.cust_nbr:
        out.append(f"{pad}  <CustNbr>{escape(order.cust_nbr)}</CustNbr>")
    out.append(f"{pad}  <OrderDate>{order.order_date.isoformat()}</OrderDate>")
    if order.event_date is not None:
        out.append(f"{pad}  <EventDate>{order.event_date.isoformat()}</EventDate>")
    out.append(f"{pad}  <Status>NEW</Status>")
    out.append(f"{pad}  <Lines>")
    for line in order.lines:
        out.append(f"{pad}    <Line>")
        out.append(f"{pad}      <LineNbr>{line.line_nbr}</LineNbr>")
        out.append(f"{pad}      <SKU>{escape(line.sku)}</SKU>")
        out.append(f"{pad}      <Qty>{line.qty}</Qty>")
        out.append(f"{pad}      <Price>{line.price:.2f}</Price>")
        out.append(f"{pad}      <FulfillType>{line.fulfill_type}</FulfillType>")
        if line.alteration is not None:
            out.append(f"{pad}      <Alteration>")
            out.append(f"{pad}        <Type>{escape(line.alteration.type)}</Type>")
            out.append(f"{pad}        <MeasurementInches>{line.alteration.measurement_inches}</MeasurementInches>")
            out.append(f"{pad}        <TailorShopNbr>{escape(line.alteration.tailor_shop_nbr)}</TailorShopNbr>")
            out.append(f"{pad}      </Alteration>")
        out.append(f"{pad}    </Line>")
    out.append(f"{pad}  </Lines>")
    out.append(f"{pad}</Order>")
    return "\n".join(out)


def to_soap_envelope(order: PosOrder) -> str:
    """SOAP 1.1 envelope with a SubmitOrderRequest body (what a legacy store's BW adapter would send)."""
    body = to_legacy_order_xml(order, indent=3)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<soapenv:Envelope xmlns:soapenv="{SOAP_NS}">\n'
        "  <soapenv:Header/>\n"
        "  <soapenv:Body>\n"
        f'    <SubmitOrderRequest xmlns="{LEGACY_NS}">\n'
        f"{body}\n"
        "    </SubmitOrderRequest>\n"
        "  </soapenv:Body>\n"
        "</soapenv:Envelope>\n"
    )
