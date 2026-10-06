import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

import pytest

from pos_simulator.generator import OrderGenerator
from pos_simulator.payloads import LEGACY_NS, SOAP_NS, to_intake_json, to_legacy_order_xml, to_soap_envelope

NOW = datetime(2026, 10, 3, 22, 14, tzinfo=timezone.utc)


def test_intake_json_matches_canonical_contract(catalog):
    gen = OrderGenerator(catalog, seed=11, now=NOW)
    order = gen.next("T", "0412")
    payload = to_intake_json(order)

    assert payload["orderType"] == "TAILORED"
    assert payload["channel"] == "STORE"
    assert payload["storeId"] == "0412"
    assert payload["currency"] == "USD"
    assert payload["orderedAt"] == "2026-10-03T22:14:00Z"
    assert payload["correlationId"] == order.correlation_id
    assert payload["promisedDate"] == "2026-10-10"  # +7 days for alterations
    assert payload["totalAmount"] == float(order.total)
    assert payload["lines"][0]["fulfillmentType"] == "STORE_PICKUP"
    alteration_lines = [l for l in payload["lines"] if l["fulfillmentType"] == "ALTERATION"]
    assert alteration_lines and alteration_lines[0]["alteration"]["tailorShopId"] == "TS-EASTBAY"
    assert payload["rental"] is None
    json.dumps(payload)  # serialisable

    rental = to_intake_json(gen.next("X"))
    assert rental["orderType"] == "RENTAL" and rental["rental"]["eventDate"] > rental["promisedDate"]

    ecom = to_intake_json(gen.next("E"))
    assert ecom["channel"] == "WEB" and ecom["shipTo"]["country"] == "US"
    assert all(l["fulfillmentType"] == "SHIP_TO_HOME" for l in ecom["lines"])


def test_legacy_xml_and_soap_envelope_are_well_formed(catalog):
    gen = OrderGenerator(catalog, seed=5, now=NOW)
    order = gen.next("T", "0875")
    xml = to_legacy_order_xml(order)
    root = ET.fromstring(xml)
    assert root.tag == f"{{{LEGACY_NS}}}Order"
    assert root.find(f"{{{LEGACY_NS}}}OrderNbr").text == order.order_nbr
    assert root.find(f"{{{LEGACY_NS}}}Lines/{{{LEGACY_NS}}}Line/{{{LEGACY_NS}}}Alteration") is not None

    envelope = ET.fromstring(to_soap_envelope(order))
    assert envelope.tag == f"{{{SOAP_NS}}}Envelope"
    body = envelope.find(f"{{{SOAP_NS}}}Body")
    request = body.find(f"{{{LEGACY_NS}}}SubmitOrderRequest")
    assert request is not None
    assert request.find(f"{{{LEGACY_NS}}}Order/{{{LEGACY_NS}}}StoreNbr").text == "0875"


@pytest.mark.skipif(shutil.which("xmllint") is None, reason="xmllint not installed")
def test_legacy_xml_validates_against_oms_xsd(catalog, xsd_path, tmp_path):
    if not xsd_path.is_file():
        pytest.skip("oms.xsd not available (running outside the monorepo checkout)")
    gen = OrderGenerator(catalog, seed=21, now=NOW)
    for i in range(40):
        doc = tmp_path / f"order-{i}.xml"
        doc.write_text('<?xml version="1.0" encoding="UTF-8"?>\n' + to_legacy_order_xml(gen.next()), encoding="utf-8")
        result = subprocess.run(
            ["xmllint", "--noout", "--schema", str(xsd_path), str(doc)], capture_output=True, text=True, check=False
        )
        assert result.returncode == 0, result.stderr
