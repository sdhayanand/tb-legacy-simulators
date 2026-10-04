"""HTTP client for order-intake-api (REST, behind Apigee) and for the SOAP adapters."""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass

import httpx

from .generator import PosOrder
from .payloads import to_intake_json, to_soap_envelope

REST_PATH = "/v1/orders"
SOAP_PATH = "/ws/orders"


@dataclass(frozen=True)
class SendResult:
    order_nbr: str
    status_code: int
    ok: bool
    elapsed_ms: float
    detail: str = ""


class IntakeClient:
    """Posts orders either as canonical JSON (`mode="rest"`) or as legacy SOAP envelopes (`mode="soap"`).

    `transport` lets tests inject `httpx.MockTransport`.
    """

    def __init__(
        self,
        target: str,
        mode: str = "rest",
        api_key: str | None = None,
        soap_path: str = SOAP_PATH,
        rest_path: str = REST_PATH,
        timeout: float = 10.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if mode not in ("rest", "soap"):
            raise ValueError("mode must be 'rest' or 'soap'")
        self.target = target.rstrip("/")
        self.mode = mode
        self.api_key = api_key
        self.soap_path = soap_path
        self.rest_path = rest_path
        headers = {"User-Agent": "tb-store-pos-simulator/0.1"}
        if api_key:
            headers["x-api-key"] = api_key  # Apigee API-key policy
        self._client = httpx.Client(base_url=self.target, headers=headers, timeout=timeout, transport=transport)

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> "IntakeClient":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def send(self, order: PosOrder) -> SendResult:
        if self.mode == "soap":
            return self._send_soap(order)
        return self._send_rest(order)

    def _send_rest(self, order: PosOrder) -> SendResult:
        headers = {
            "Content-Type": "application/json",
            "X-Correlation-Id": order.correlation_id,
            "Idempotency-Key": str(uuid.uuid5(uuid.NAMESPACE_URL, order.order_nbr)),
        }
        started = time.perf_counter()
        try:
            response = self._client.post(self.rest_path, json=to_intake_json(order), headers=headers)
        except httpx.HTTPError as exc:  # connection refused, timeout, ...
            return SendResult(order.order_nbr, 0, False, _ms(started), f"{type(exc).__name__}: {exc}")
        return self._result(order, response, started)

    def _send_soap(self, order: PosOrder) -> SendResult:
        headers = {
            "Content-Type": "text/xml; charset=utf-8",
            "SOAPAction": "SubmitOrder",
            "X-Correlation-Id": order.correlation_id,
        }
        started = time.perf_counter()
        try:
            response = self._client.post(self.soap_path, content=to_soap_envelope(order).encode("utf-8"), headers=headers)
        except httpx.HTTPError as exc:
            return SendResult(order.order_nbr, 0, False, _ms(started), f"{type(exc).__name__}: {exc}")
        ok = response.is_success and ":Fault>" not in response.text and "<Fault>" not in response.text
        return self._result(order, response, started, ok=ok)

    @staticmethod
    def _result(order: PosOrder, response: httpx.Response, started: float, ok: bool | None = None) -> SendResult:
        success = response.is_success if ok is None else ok
        detail = "" if success else response.text[:300]
        return SendResult(order.order_nbr, response.status_code, success, _ms(started), detail)


def _ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000, 1)
