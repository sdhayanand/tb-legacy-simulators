"""Generates realistic POS orders - the same business rules as ems-broker's LegacyOrderGenerator (Java)."""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

from .catalog import Catalog, Store

ORDER_TYPES = ("R", "T", "C", "X", "E")
CANONICAL_TYPE = {"R": "RETAIL", "T": "TAILORED", "C": "CUSTOM", "X": "RENTAL", "E": "ECOM"}
CANONICAL_FULFILL = {"P": "STORE_PICKUP", "S": "SHIP_TO_HOME", "A": "ALTERATION"}


@dataclass(frozen=True)
class Alteration:
    type: str
    measurement_inches: Decimal
    tailor_shop_nbr: str


@dataclass(frozen=True)
class OrderLine:
    line_nbr: int
    sku: str
    qty: int
    price: Decimal
    fulfill_type: str  # P / S / A
    alteration: Alteration | None = None

    @property
    def extended(self) -> Decimal:
        return (self.price * self.qty).quantize(Decimal("0.01"))


@dataclass(frozen=True)
class PosOrder:
    order_nbr: str
    order_type: str  # R / T / C / X / E
    store_nbr: str
    cust_nbr: str | None
    order_date: date
    ordered_at: datetime
    event_date: date | None
    lines: tuple[OrderLine, ...]
    register: str = "01"
    associate_id: str = "A1001"
    extra: dict = field(default_factory=dict)

    @property
    def total(self) -> Decimal:
        return sum((line.extended for line in self.lines), Decimal("0.00")).quantize(Decimal("0.01"))

    @property
    def correlation_id(self) -> str:
        txn = self.order_nbr.rsplit("-", 1)[-1]
        return f"store-{self.store_nbr}-txn-{txn}"

    @property
    def has_alteration(self) -> bool:
        return any(line.alteration is not None for line in self.lines)


class OrderGenerator:
    """Deterministic when given a seed; thread-unsafe (one per process is plenty)."""

    def __init__(
        self,
        catalog: Catalog,
        stores: list[Store] | None = None,
        seed: int | None = None,
        now: datetime | None = None,
    ) -> None:
        self.catalog = catalog
        self.stores = stores or list(catalog.stores)
        self.random = random.Random(seed)
        self._now = now
        self._txn: dict[str, int] = {}
        self._weighted = [t for t, w in catalog.order_type_weights.items() for _ in range(w)]
        if not self._weighted:
            raise ValueError("orderTypeWeights is empty")

    # -- public -----------------------------------------------------------------------------------------

    def random_type(self) -> str:
        return self.random.choice(self._weighted)

    def next(self, order_type: str | None = None, store_nbr: str | None = None) -> PosOrder:
        order_type = order_type or self.random_type()
        if order_type not in ORDER_TYPES:
            raise ValueError(f"Unknown order type {order_type}; expected one of {ORDER_TYPES}")
        store = self.catalog.store(store_nbr) if store_nbr else self.random.choice(self.stores)
        effective_store = self.catalog.ecom_store_nbr if order_type == "E" else store.store_nbr
        now = self._now or datetime.now(timezone.utc)
        today = now.date()
        order_nbr = self._next_order_nbr(effective_store, today)
        cust_nbr = self._customer(order_type)
        event_date = None

        if order_type == "T":
            lines = self._tailored_lines(store)
        elif order_type == "X":
            lines = self._rental_lines()
            event_date = today + timedelta(weeks=2 + self.random.randint(0, 8), days=self.random.randint(0, 6))
        elif order_type == "E":
            lines = self._ecom_lines()
        elif order_type == "C":
            lines = self._custom_lines()
        else:
            lines = self._retail_lines()

        return PosOrder(
            order_nbr=order_nbr,
            order_type=order_type,
            store_nbr=effective_store,
            cust_nbr=cust_nbr,
            order_date=today,
            ordered_at=now.replace(microsecond=0),
            event_date=event_date,
            lines=tuple(lines),
            register=f"{self.random.randint(1, 4):02d}",
            associate_id=f"A{self.random.randint(1000, 9999)}",
        )

    # -- internals --------------------------------------------------------------------------------------

    def _next_order_nbr(self, store_nbr: str, day: date) -> str:
        if store_nbr not in self._txn:
            self._txn[store_nbr] = 100 + self.random.randint(0, 899)
        self._txn[store_nbr] += 1
        return f"{store_nbr}-{day.strftime('%y%m%d')}-{self._txn[store_nbr]:06d}"

    def _customer(self, order_type: str) -> str | None:
        if order_type == "R" and self.random.randint(0, 99) < 30:
            return None  # walk-in without loyalty number
        return f"C-{self.random.randint(10000, 99999)}"

    def _retail_lines(self) -> list[OrderLine]:
        pool = self.catalog.skus_in("SUIT", "SPORTCOAT", "TROUSER", "SHIRT", "ACCESSORY", "SHOES", "OUTERWEAR")
        count = self.random.randint(1, 3)
        return [
            OrderLine(i, s.sku, 1 if self.random.randint(0, 9) < 8 else 2, s.price, "P")
            for i, s in enumerate((self.random.choice(pool) for _ in range(count)), start=1)
        ]

    def _tailored_lines(self, store: Store) -> list[OrderLine]:
        suit = self.random.choice(self.catalog.skus_in("SUIT"))
        lines = [OrderLine(1, suit.sku, 1, suit.price, "P")]
        services = list(self.catalog.alterations)
        for _ in range(self.random.randint(1, 2)):
            if not services:
                break
            service = services.pop(self.random.randrange(len(services)))
            alt_sku = self.catalog.sku(service.sku)
            inches = round(self.random.uniform(service.min_inches, service.max_inches) * 2) / 2
            lines.append(
                OrderLine(
                    len(lines) + 1,
                    alt_sku.sku,
                    1,
                    alt_sku.price,
                    "A",
                    Alteration(service.type, Decimal(str(inches)).quantize(Decimal("0.1")), store.tailor_shop_nbr),
                )
            )
        return lines

    def _rental_lines(self) -> list[OrderLine]:
        pool = list(self.catalog.skus_in("RENTAL"))
        lines: list[OrderLine] = []
        for i in range(1, self.random.randint(1, 3) + 1):
            if not pool:
                break
            s = pool.pop(self.random.randrange(len(pool)))
            lines.append(OrderLine(i, s.sku, 1, s.price, "P"))
        return lines

    def _ecom_lines(self) -> list[OrderLine]:
        pool = self.catalog.skus_in("SHIRT", "ACCESSORY", "OUTERWEAR", "TROUSER", "SHOES")
        return [
            OrderLine(i, s.sku, 1, s.price, "S")
            for i, s in enumerate((self.random.choice(pool) for _ in range(self.random.randint(1, 3))), start=1)
        ]

    def _custom_lines(self) -> list[OrderLine]:
        s = self.random.choice(self.catalog.skus_in("CUSTOM"))
        return [OrderLine(1, s.sku, 1, s.price, "S")]
