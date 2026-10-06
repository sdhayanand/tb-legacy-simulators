"""Every generated payload must pass order-intake-api's rules (CreateOrderRequest + OrderValidator).

Regression for the ~25% of POS orders the live API rejected: catalog SKUs such as `34x32` / `16.5`
broke the canonical SKU pattern, ECOM orders sent channel "ECOM" (enum is STORE/WEB/CALL_CENTER), and
rental details used field names the API does not read."""

import re
from datetime import datetime, timezone
from decimal import Decimal

import pytest

from pos_simulator.generator import ORDER_TYPES, OrderGenerator
from pos_simulator.payloads import to_intake_json

SKU = re.compile(r"[A-Z0-9][A-Z0-9\-]*")  # CreateOrderLineRequest.sku / Apigee OAS
CHANNELS = {"STORE", "WEB", "CALL_CENTER"}
ORDER_TYPES_API = {"RETAIL", "TAILORED", "CUSTOM", "RENTAL", "ECOM"}
FULFILLMENT = {"STORE_PICKUP", "SHIP_TO_HOME", "ALTERATION"}
RENTAL_FIELDS = {"eventId", "eventDate", "returnDueDate", "groupId"}
NOW = datetime(2026, 10, 5, 17, 0, tzinfo=timezone.utc)


def test_every_catalog_sku_matches_the_canonical_pattern(catalog):
    bad = [s.sku for s in catalog.skus if not SKU.fullmatch(s.sku)]
    assert bad == []


@pytest.mark.parametrize("order_type", ORDER_TYPES)
def test_generated_orders_pass_order_intake_rules(catalog, order_type):
    gen = OrderGenerator(catalog, seed=7, now=NOW)
    for _ in range(200):
        p = to_intake_json(gen.next(order_type))
        assert p["orderType"] in ORDER_TYPES_API
        assert p["channel"] in CHANNELS
        assert re.fullmatch(r"\d{4}", p["storeId"])
        total = Decimal("0")
        for line in p["lines"]:
            assert SKU.fullmatch(line["sku"]), line["sku"]
            assert line["fulfillmentType"] in FULFILLMENT
            assert (line["fulfillmentType"] == "ALTERATION") == ("alteration" in line)
            if line["fulfillmentType"] == "SHIP_TO_HOME":
                assert p["shipTo"] and p["shipTo"]["line1"] and p["shipTo"]["postalCode"]
            total += Decimal(str(line["unitPrice"])) * line["quantity"]
        assert Decimal(str(p["totalAmount"])) == total
        if p["orderType"] == "RENTAL":
            assert p["rental"] and set(p["rental"]) <= RENTAL_FIELDS
