import json

import pytest

from pos_simulator.catalog import candidate_paths, load_catalog, parse_catalog


def test_catalog_shape(catalog):
    assert len(catalog.stores) == 25
    assert len(catalog.skus) == 40
    assert all(len(s.store_nbr) == 4 and s.store_nbr.isdigit() for s in catalog.stores)
    assert set(catalog.order_type_weights) == {"R", "T", "X", "E", "C"}
    assert catalog.ecom_store_nbr == "9001"
    assert {a.type for a in catalog.alterations} == {"HEM", "SLEEVE", "WAIST", "TAPER"}
    assert all(catalog.sku(a.sku).category == "ALTERATION" for a in catalog.alterations)
    assert all(s.price > 0 for s in catalog.skus)


def test_store_subset_and_errors(catalog):
    subset = catalog.stores_subset(["0412", " 0875 "])
    assert [s.store_nbr for s in subset] == ["0412", "0875"]
    assert catalog.stores_subset(None) == list(catalog.stores)
    with pytest.raises(KeyError):
        catalog.stores_subset(["9999"])
    with pytest.raises(KeyError):
        catalog.sku("NOPE")


def test_catalog_path_resolution(monkeypatch, tmp_path):
    monkeypatch.setenv("CATALOG_PATH", str(tmp_path / "from-env.json"))
    paths = candidate_paths(tmp_path / "explicit.json")
    assert paths[0] == tmp_path / "explicit.json"
    assert paths[1] == tmp_path / "from-env.json"
    assert paths[2].name == "catalog.json"


def test_catalog_path_env_is_used(monkeypatch, tmp_path, catalog):
    small = {
        "ecomStoreNbr": "9001",
        "orderTypeWeights": {"R": 1},
        "stores": [{"storeNbr": "0001", "name": "Test", "tailorShopNbr": "TS-T"}],
        "skus": [{"sku": "X-1", "category": "SUIT", "price": 10}],
        "alterations": [{"type": "HEM", "sku": "X-1", "minInches": 1, "maxInches": 2}],
    }
    path = tmp_path / "catalog.json"
    path.write_text(json.dumps(small))
    monkeypatch.setenv("CATALOG_PATH", str(path))
    loaded = load_catalog()
    assert [s.store_nbr for s in loaded.stores] == ["0001"]
    assert loaded.skus[0].price == 10


def test_parse_catalog_rejects_empty_sections():
    with pytest.raises((ValueError, KeyError)):
        parse_catalog({"stores": [], "skus": [], "alterations": [], "orderTypeWeights": {}})
