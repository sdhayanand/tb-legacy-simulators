from pathlib import Path

import pytest

from pos_simulator.catalog import load_catalog

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def catalog():
    return load_catalog(ROOT / "catalog.json")


@pytest.fixture(scope="session")
def xsd_path() -> Path:
    return ROOT / "legacy-oms-soap" / "src" / "main" / "resources" / "xsd" / "oms.xsd"
