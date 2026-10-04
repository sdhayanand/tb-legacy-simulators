"""Loads the shared catalog.json (stores, SKUs, alteration services, order-type weights).

Resolution order: explicit path argument, CATALOG_PATH env var, repo root (../catalog.json relative to this
package), then a catalog.json next to this package (what the Docker image ships).
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class Store:
    store_nbr: str
    name: str
    banner: str
    state: str
    region: str
    tailor_shop_nbr: str


@dataclass(frozen=True)
class Sku:
    sku: str
    description: str
    category: str
    banner: str
    price: Decimal


@dataclass(frozen=True)
class AlterationService:
    type: str
    sku: str
    min_inches: float
    max_inches: float


@dataclass(frozen=True)
class Catalog:
    ecom_store_nbr: str
    order_type_weights: dict[str, int]
    stores: tuple[Store, ...]
    skus: tuple[Sku, ...]
    alterations: tuple[AlterationService, ...]

    def skus_in(self, *categories: str) -> list[Sku]:
        wanted = set(categories)
        return [s for s in self.skus if s.category in wanted]

    def sku(self, code: str) -> Sku:
        for s in self.skus:
            if s.sku == code:
                return s
        raise KeyError(f"Unknown SKU {code}")

    def store(self, store_nbr: str) -> Store:
        for s in self.stores:
            if s.store_nbr == store_nbr:
                return s
        raise KeyError(f"Unknown store {store_nbr}")

    def stores_subset(self, store_nbrs: Iterable[str] | None) -> list[Store]:
        if not store_nbrs:
            return list(self.stores)
        wanted = {s.strip() for s in store_nbrs if s.strip()}
        subset = [s for s in self.stores if s.store_nbr in wanted]
        missing = wanted - {s.store_nbr for s in subset}
        if missing:
            raise KeyError(f"Unknown store number(s): {', '.join(sorted(missing))}")
        return subset


def candidate_paths(explicit: str | os.PathLike[str] | None = None) -> list[Path]:
    here = Path(__file__).resolve().parent
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    env = os.environ.get("CATALOG_PATH")
    if env:
        candidates.append(Path(env))
    candidates.append(here.parent.parent / "catalog.json")  # repo root when run from the source tree
    candidates.append(here.parent / "catalog.json")  # store-pos-simulator/catalog.json (Docker image)
    candidates.append(here / "catalog.json")
    return candidates


def load_catalog(path: str | os.PathLike[str] | None = None) -> Catalog:
    for candidate in candidate_paths(path):
        if candidate.is_file():
            return parse_catalog(json.loads(candidate.read_text(encoding="utf-8")))
    searched = ", ".join(str(p) for p in candidate_paths(path))
    raise FileNotFoundError(f"catalog.json not found (searched: {searched}); set CATALOG_PATH")


def parse_catalog(raw: dict) -> Catalog:
    stores = tuple(
        Store(
            store_nbr=str(s["storeNbr"]),
            name=s.get("name", ""),
            banner=s.get("banner", ""),
            state=s.get("state", ""),
            region=s.get("region", ""),
            tailor_shop_nbr=s.get("tailorShopNbr", ""),
        )
        for s in raw["stores"]
    )
    skus = tuple(
        Sku(
            sku=s["sku"],
            description=s.get("description", ""),
            category=s["category"],
            banner=s.get("banner", ""),
            price=Decimal(str(s["price"])).quantize(Decimal("0.01")),
        )
        for s in raw["skus"]
    )
    alterations = tuple(
        AlterationService(
            type=a["type"],
            sku=a["sku"],
            min_inches=float(a["minInches"]),
            max_inches=float(a["maxInches"]),
        )
        for a in raw["alterations"]
    )
    weights = {str(k): int(v) for k, v in raw["orderTypeWeights"].items()}
    if not stores or not skus or not alterations or not weights:
        raise ValueError("catalog.json is missing stores, skus, alterations or orderTypeWeights")
    return Catalog(
        ecom_store_nbr=str(raw.get("ecomStoreNbr", "9001")),
        order_type_weights=weights,
        stores=stores,
        skus=skus,
        alterations=alterations,
    )
