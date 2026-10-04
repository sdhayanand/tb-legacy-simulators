"""Command line: generate POS orders and post them to order-intake-api.

Examples
    pos-simulator --target http://localhost:8080 --count 10 --rate 60
    pos-simulator --mode soap --target http://localhost:8085 --soap-path /ws --count 3
    pos-simulator --dry-run --count 2 --stores 0412,0875 --type T
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import sys
import time
from datetime import datetime, timezone

from . import __version__
from .catalog import load_catalog
from .client import REST_PATH, SOAP_PATH, IntakeClient
from .generator import ORDER_TYPES, OrderGenerator
from .payloads import to_intake_json, to_soap_envelope


def build_parser() -> argparse.ArgumentParser:
    env = os.environ.get
    p = argparse.ArgumentParser(prog="pos-simulator", description="Tailored Brands store POS order simulator")
    p.add_argument("--target", default=env("POS_TARGET", "http://localhost:8080"),
                   help="base URL of order-intake-api (or Apigee proxy) [POS_TARGET]")
    p.add_argument("--mode", choices=("rest", "soap"), default=env("POS_MODE", "rest"),
                   help="rest: POST canonical JSON to /v1/orders; soap: POST SubmitOrderRequest envelopes [POS_MODE]")
    p.add_argument("--rate", type=float, default=float(env("POS_RATE", "30")),
                   help="orders per minute (0 = as fast as possible) [POS_RATE]")
    p.add_argument("--count", type=int, default=int(env("POS_COUNT", "10")),
                   help="number of orders to send (0 = run until interrupted) [POS_COUNT]")
    p.add_argument("--stores", default=env("POS_STORES", ""),
                   help="comma-separated store numbers to simulate (default: all 25) [POS_STORES]")
    p.add_argument("--type", dest="order_type", choices=ORDER_TYPES, default=env("POS_ORDER_TYPE") or None,
                   help="force one order type (R/T/C/X/E) instead of the weighted mix [POS_ORDER_TYPE]")
    p.add_argument("--api-key", default=env("POS_API_KEY"), help="Apigee API key sent as x-api-key [POS_API_KEY]")
    p.add_argument("--rest-path", default=env("POS_REST_PATH", REST_PATH))
    p.add_argument("--soap-path", default=env("POS_SOAP_PATH", SOAP_PATH),
                   help="SOAP endpoint path (order-intake-api adapter: /ws/orders; legacy-oms-soap: /ws)")
    p.add_argument("--catalog", default=None, help="path to catalog.json (default: CATALOG_PATH / repo root)")
    p.add_argument("--seed", type=int, default=None, help="deterministic generation")
    p.add_argument("--timeout", type=float, default=10.0)
    p.add_argument("--dry-run", action="store_true", help="print payloads instead of sending")
    p.add_argument("--quiet", action="store_true")
    p.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    return p


def run(args: argparse.Namespace) -> int:
    catalog = load_catalog(args.catalog)
    stores = catalog.stores_subset(args.stores.split(",") if args.stores else None)
    generator = OrderGenerator(catalog, stores=stores, seed=args.seed)
    interval = 60.0 / args.rate if args.rate and args.rate > 0 else 0.0

    stop = {"flag": False}

    def _stop(_signum, _frame):  # pragma: no cover - signal handling
        stop["flag"] = True

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    sent = ok = failed = 0
    started = time.monotonic()
    client = None if args.dry_run else IntakeClient(
        args.target, mode=args.mode, api_key=args.api_key, soap_path=args.soap_path,
        rest_path=args.rest_path, timeout=args.timeout,
    )
    try:
        while not stop["flag"] and (args.count == 0 or sent < args.count):
            order = generator.next(args.order_type)
            sent += 1
            if args.dry_run:
                print(to_soap_envelope(order) if args.mode == "soap" else json.dumps(to_intake_json(order), indent=2))
                ok += 1
            else:
                result = client.send(order)
                if result.ok:
                    ok += 1
                else:
                    failed += 1
                if not args.quiet:
                    stamp = datetime.now(timezone.utc).strftime("%H:%M:%S")
                    status = "OK " if result.ok else "ERR"
                    print(f"{stamp} {status} {order.order_type} {order.order_nbr} store={order.store_nbr} "
                          f"lines={len(order.lines)} total={order.total} http={result.status_code} "
                          f"{result.elapsed_ms}ms {result.detail}".rstrip())
            if interval > 0 and (args.count == 0 or sent < args.count):
                time.sleep(interval)
    finally:
        if client is not None:
            client.close()

    elapsed = time.monotonic() - started
    print(f"done: sent={sent} ok={ok} failed={failed} mode={args.mode} target={args.target} "
          f"elapsed={elapsed:.1f}s", file=sys.stderr)
    return 0 if failed == 0 else 1


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return run(args)
    except (FileNotFoundError, KeyError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
