import json

import httpx

from pos_simulator import cli
from pos_simulator.client import IntakeClient
from pos_simulator.generator import OrderGenerator


def test_rest_client_posts_canonical_json_with_api_key(catalog):
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["headers"] = dict(request.headers)
        captured["body"] = json.loads(request.content)
        return httpx.Response(201, json={"orderId": captured["body"]["orderId"], "status": "ACCEPTED"})

    order = OrderGenerator(catalog, seed=2).next("R", "1021")
    with IntakeClient("http://intake.test/", api_key="k-123", transport=httpx.MockTransport(handler)) as client:
        result = client.send(order)

    assert result.ok and result.status_code == 201
    assert captured["url"] == "http://intake.test/v1/orders"
    assert captured["headers"]["x-api-key"] == "k-123"
    assert captured["headers"]["x-correlation-id"] == order.correlation_id
    assert captured["body"]["storeId"] == "1021"
    assert captured["body"]["orderType"] == "RETAIL"


def test_soap_client_posts_envelope_and_detects_faults(catalog):
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        body = request.content.decode("utf-8")
        if "StoreNbr>0412<" in body:
            return httpx.Response(200, text="<soapenv:Envelope><soapenv:Body><SubmitOrderResponse/></soapenv:Body></soapenv:Envelope>")
        return httpx.Response(500, text="<soapenv:Envelope><soapenv:Body><soapenv:Fault><faultstring>bad</faultstring></soapenv:Fault></soapenv:Body></soapenv:Envelope>")

    gen = OrderGenerator(catalog, seed=9)
    with IntakeClient("http://oms.test", mode="soap", soap_path="/ws", transport=httpx.MockTransport(handler)) as client:
        good = client.send(gen.next("R", "0412"))
        bad = client.send(gen.next("R", "0875"))

    assert good.ok
    assert not bad.ok and "Fault" in bad.detail
    assert calls[0].headers["content-type"].startswith("text/xml")
    assert calls[0].headers["soapaction"] == "SubmitOrder"
    assert str(calls[0].url) == "http://oms.test/ws"
    assert b"<SubmitOrderRequest" in calls[0].content


def test_client_reports_connection_errors_instead_of_raising(catalog):
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    order = OrderGenerator(catalog, seed=4).next()
    with IntakeClient("http://down.test", transport=httpx.MockTransport(handler)) as client:
        result = client.send(order)
    assert not result.ok and result.status_code == 0 and "ConnectError" in result.detail


def test_cli_dry_run_prints_payloads(capsys):
    rc = cli.main(["--dry-run", "--count", "2", "--rate", "0", "--seed", "1", "--stores", "0412,0875", "--type", "T"])
    assert rc == 0
    out = capsys.readouterr().out
    assert out.count('"orderType": "TAILORED"') == 2
    assert '"storeId": "0412"' in out or '"storeId": "0875"' in out

    rc = cli.main(["--dry-run", "--count", "1", "--rate", "0", "--mode", "soap", "--seed", "1"])
    assert rc == 0
    assert "<SubmitOrderRequest" in capsys.readouterr().out


def test_cli_rejects_unknown_store(capsys):
    rc = cli.main(["--dry-run", "--count", "1", "--stores", "4242"])
    assert rc == 2
    assert "Unknown store" in capsys.readouterr().err
