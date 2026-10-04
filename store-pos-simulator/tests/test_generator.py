import re
from collections import Counter
from datetime import date, datetime, timezone
from decimal import Decimal

from pos_simulator.generator import ORDER_TYPES, OrderGenerator

ORDER_NBR = re.compile(r"^\d{4}-\d{6}-\d{6}$")


def test_order_type_distribution_follows_weights(catalog):
    gen = OrderGenerator(catalog, seed=42)
    counts = Counter(gen.next().order_type for _ in range(4000))
    assert set(counts) <= set(ORDER_TYPES)
    assert 0.42 < counts["R"] / 4000 < 0.58
    assert 0.18 < counts["T"] / 4000 < 0.32
    assert 0.09 < counts["X"] / 4000 < 0.21
    assert 0.03 < counts["E"] / 4000 < 0.12
    assert 0.005 < counts["C"] / 4000 < 0.07


def test_deterministic_with_seed(catalog):
    a = [OrderGenerator(catalog, seed=7).next() for _ in range(5)]
    b = [OrderGenerator(catalog, seed=7).next() for _ in range(5)]
    assert [o.order_nbr for o in a] == [o.order_nbr for o in b]
    assert [o.lines for o in a] == [o.lines for o in b]


def test_business_rules_per_order_type(catalog):
    now = datetime(2026, 10, 3, 14, 30, tzinfo=timezone.utc)
    gen = OrderGenerator(catalog, seed=1, now=now)

    tailored = gen.next("T", "0412")
    assert tailored.store_nbr == "0412"
    assert tailored.order_date == date(2026, 10, 3)
    assert tailored.has_alteration
    assert tailored.lines[0].fulfill_type == "P"
    for line in tailored.lines[1:]:
        assert line.fulfill_type == "A"
        assert line.alteration.tailor_shop_nbr == "TS-EASTBAY"
        assert line.alteration.measurement_inches > 0

    rental = gen.next("X")
    assert rental.event_date is not None and rental.event_date > rental.order_date
    assert all("RENTAL" in line.sku for line in rental.lines)

    ecom = gen.next("E")
    assert ecom.store_nbr == catalog.ecom_store_nbr
    assert all(line.fulfill_type == "S" for line in ecom.lines)

    custom = gen.next("C")
    assert len(custom.lines) == 1 and "CUSTOM" in custom.lines[0].sku

    retail = gen.next("R")
    assert 1 <= len(retail.lines) <= 3
    assert retail.total == sum((l.price * l.qty for l in retail.lines), Decimal("0.00"))


def test_order_numbers_unique_and_correlation_id(catalog):
    gen = OrderGenerator(catalog, seed=3, stores=catalog.stores_subset(["0412", "0875"]))
    seen = set()
    for _ in range(300):
        order = gen.next()
        assert ORDER_NBR.match(order.order_nbr), order.order_nbr
        assert order.store_nbr in ("0412", "0875", catalog.ecom_store_nbr)
        assert order.correlation_id == f"store-{order.store_nbr}-txn-{order.order_nbr[-6:]}"
        assert order.order_nbr not in seen
        seen.add(order.order_nbr)
