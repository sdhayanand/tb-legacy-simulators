# tb-legacy-simulators

Realistic stand-ins for the legacy systems the Tailored Brands Order-to-Delivery migration starts from
(see `tb-platform-infra/docs/ARCHITECTURE.md` §1 and §7): the TIBCO EMS / BusinessWorks messaging backbone,
the IBM MQ feed into the ERP, and the Oracle-backed Order Management System with its SOAP contract and nightly
extract. They are what the bridges in `tb-tibco-to-pubsub-migration` connect to, what the Dataflow batch
reconciliation reads, and what the SOAP adapter in `order-intake-api` was written against.

| Module | Stands in for | Stack | Port(s) |
|---|---|---|---|
| [`legacy-oms-soap`](legacy-oms-soap) | Oracle OMS: contract-first SOAP (`SubmitOrder`, `GetOrderStatus`, `ExportOrders`), `ORD_HDR`/`ORD_LINE` schema, nightly `<Orders>` extract to GCS | Spring WS + XSD/xjc, H2 in **Oracle mode**, JdbcTemplate, google-cloud-storage | 8085 |
| [`ems-broker`](ems-broker) | TIBCO EMS + the BW "POS order publisher" process | Embedded ActiveMQ Artemis (TCP acceptor 61616), scheduled publisher of legacy XML orders | 61616 JMS, 8086 HTTP |
| [`erp-mq-consumer`](erp-mq-consumer) | ERP adapter consuming `ERP.ORDERS.IN` from IBM MQ | IBM MQ JMS client 9.4 (`JMS_PROVIDER=artemis` fallback), Spring JMS | 8087 |
| [`store-pos-simulator`](store-pos-simulator) | 25 store POS registers | Python 3.12 + httpx; REST to `order-intake-api` or legacy SOAP envelopes; GKE CronJob | — |
| [`deploy/k8s/ibm-mq`](deploy/k8s/ibm-mq) | IBM MQ queue manager `QM1` | `icr.io/ibm-messaging/mq` StatefulSet + MQSC ConfigMap | 1414, 9443 |

All four simulators share one data set: [`catalog.json`](catalog.json) (25 stores, 40 Men's Wearhouse /
Jos. A. Bank style SKUs with prices, alteration services, order-type weights R 50 % / T 25 % / X 15 % / E 7 % / C 3 %).
The XML vocabulary is [`legacy-oms-soap/src/main/resources/xsd/oms.xsd`](legacy-oms-soap/src/main/resources/xsd/oms.xsd)
and the three canned messages in [`ems-broker/src/main/resources/samples`](ems-broker/src/main/resources/samples)
are the examples the other repos' docs refer to. The fictional estate behind all this is described in
[`docs/LEGACY-LANDSCAPE.md`](docs/LEGACY-LANDSCAPE.md).

## How this maps to the job posting

* *TIBCO EMS / BW and IBM MQ experience* - the simulators reproduce the real interfaces (queue names, header
  properties, `MQRFH2`-style JMS over MQ, XSD-validated XML, SOAP faults) so the migration can be rehearsed end to end.
* *Migrating legacy middleware to Google Cloud* - this is the "before" side; the "after" side is Pub/Sub, GKE,
  Dataflow and the bridges. Phase 0 (`LEGACY_ONLY`) of the runbook runs entirely on this repo.
* *SOAP/XML, XSD, WSDL, contract-first* - `oms.xsd` → xjc → `@Endpoint`, WSDL generated from the schema, payload
  validation interceptor, typed faults.
* *Oracle / SQL* - Oracle-dialect DDL and queries (`VARCHAR2`, `NUMBER`, sequences, `DUAL`) running on H2 in Oracle mode.
* *Batch interfaces* - the nightly `<Orders>` extract that the Dataflow reconciliation consumes from GCS.
* *Kubernetes, CI/CD* - Jib images to GHCR and Artifact Registry, kustomize overlays, GKE Autopilot via Workload Identity.

## Run locally

### docker compose (everything)

```bash
docker compose up --build            # ems-broker, legacy-oms-soap, ibm-mq, erp-mq-consumer
docker compose --profile artemis-erp up -d erp-mq-consumer-artemis   # ERP consumer without IBM MQ (port 8088)
```

Ports: `61616` (EMS stand-in), `8085` (OMS), `8086` (simulator API), `1414`/`9443` (MQ, console `admin`/`passw0rd`),
`8087` (ERP). The MQ image is ~1 GB and takes about a minute to start; the ERP consumer waits for it.

### Maven (one module at a time)

```bash
mvn -q test                                        # unit tests, no Docker needed
mvn verify                                         # + ArtemisConsumerIT (Testcontainers)
RUN_MQ_IT=true mvn -pl erp-mq-consumer verify      # + IbmMqConsumerIT (pulls icr.io/ibm-messaging/mq)

mvn -pl ems-broker spring-boot:run                 # PUBLISH_RATE_PER_MIN=6 by default
mvn -pl legacy-oms-soap spring-boot:run
JMS_PROVIDER=artemis mvn -pl erp-mq-consumer spring-boot:run
```

### Python POS simulator

```bash
cd store-pos-simulator
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/pytest
.venv/bin/python -m pos_simulator --target http://localhost:8080 --count 20 --rate 60 --api-key $API_KEY
.venv/bin/python -m pos_simulator --mode soap --target http://localhost:8085 --soap-path /ws --count 3
.venv/bin/python -m pos_simulator --dry-run --count 1 --type T --stores 0412
```

## Verify it works

```bash
# 1. EMS stand-in: publish 3 legacy orders and look at the counters
curl -s -X POST 'http://localhost:8086/simulate/orders?count=3' | jq
#   {"published":3,"destination":"TB.ORDERS.OUT","orderNbrs":["0412-261003-000871", ...]}
curl -s http://localhost:8086/stats | jq '.published, .queueDepths'
curl -s http://localhost:8086/samples/tailored            # canned <Order> XML
curl -s -X POST http://localhost:8086/simulate/cancel/0412-261003-000871 | jq .destination   # "TB.ORDERS.CANCEL"

# 2. OMS: WSDL, SubmitOrder over SOAP, status, extract
curl -s http://localhost:8085/ws/oms.wsdl | grep -o 'operation name="[A-Za-z]*"' | sort -u
#   operation name="ExportOrders" / "GetOrderStatus" / "SubmitOrder"
curl -s -H 'Content-Type: text/xml' -H 'SOAPAction: SubmitOrder' --data-binary @- http://localhost:8085/ws <<'EOF'
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>
<SubmitOrderRequest xmlns="http://tailoredbrands.com/legacy/oms/v1"><Order>
  <OrderNbr>0412-261003-889213</OrderNbr><OrderType>T</OrderType><StoreNbr>0412</StoreNbr><CustNbr>C-77812</CustNbr>
  <OrderDate>2026-10-03</OrderDate><Lines>
  <Line><LineNbr>1</LineNbr><SKU>MW-SUIT-NAVY-42R</SKU><Qty>1</Qty><Price>599.99</Price><FulfillType>P</FulfillType></Line>
  <Line><LineNbr>2</LineNbr><SKU>ALT-HEM-TROUSER</SKU><Qty>1</Qty><Price>25.00</Price><FulfillType>A</FulfillType>
    <Alteration><Type>HEM</Type><MeasurementInches>31.5</MeasurementInches><TailorShopNbr>TS-EASTBAY</TailorShopNbr></Alteration></Line>
  </Lines></Order></SubmitOrderRequest></soapenv:Body></soapenv:Envelope>
EOF
#   <SubmitOrderResponse><OrderNbr>0412-261003-889213</OrderNbr><Status>IN_ALTERATION</Status><Message>ACCEPTED</Message>...
curl -s http://localhost:8085/admin/orders/0412-261003-889213 | jq .status      # "IN_ALTERATION"
curl -s 'http://localhost:8085/admin/export?date=2026-10-01' | head -5           # <Orders ExtractDate="2026-10-01" Count="9">
curl -s -X POST 'http://localhost:8085/admin/export-to-gcs?date=2026-10-01&bucket=my-bucket' | jq .status   # WRITTEN or SKIPPED

# 3. ERP: put a message on ERP.ORDERS.IN (via the bridge, or straight on MQ) and read it back
curl -s 'http://localhost:8087/received?limit=5' | jq '.[] | {orderNbr, storeNbr, orderType, totalAmount}'
curl -s http://localhost:8087/received/stats | jq .perStore
curl -s -X DELETE http://localhost:8087/received
```

A full legacy loop with the migration repo: `jms-to-pubsub-bridge` (`JMS_URL=tcp://localhost:61616`) reads
`TB.ORDERS.OUT` and publishes to `orders-v1`; `pubsub-to-jms-bridge` consumes `orders-to-legacy-mq` and puts on
IBM MQ `ERP.ORDERS.IN`; `erp-mq-consumer` shows it under `/received`.

## CI/CD

* [`ci.yml`](.github/workflows/ci.yml): `mvn verify` (unit tests + `ArtemisConsumerIT` via Testcontainers) and the
  Python tests on every push/PR; on `main` it pushes the three Java images with Jib and the Python image with
  `docker build` to `ghcr.io/sdhayanand/tb-*`, and runs the IBM MQ integration test (`RUN_MQ_IT=true`) against
  `icr.io/ibm-messaging/mq` in a separate job.
* [`deploy-gcp.yml`](.github/workflows/deploy-gcp.yml) (guarded by `vars.GCP_PROJECT_ID`): Workload Identity
  Federation, Jib to Artifact Registry, then `kubectl apply -k deploy/k8s/overlays/gcp` into namespace `legacy`
  (IBM MQ StatefulSet + MQSC ConfigMap, the three Deployments/Services, the POS CronJob). The MQ `app` password comes
  from the `MQ_APP_PASSWORD` secret (default `passw0rd` for the demo).
* Every module has a multi-stage `Dockerfile` for compose, Jib config for CI, and `deploy/k8s/{base,overlays/gcp,overlays/local}`.

## How these map to the real TIBCO EMS / BW / IBM MQ / Oracle OMS

| Real thing | Simulator | What is faithful | What is simplified |
|---|---|---|---|
| **TIBCO EMS** server (`tcp://ems-prod:7222`, FT pair, queues `TB.ORDERS.*`) | `ems-broker`: embedded ActiveMQ Artemis with a Netty acceptor on `tcp://0.0.0.0:61616` and the same queue names | JMS 2/3 API semantics (queues, `TextMessage`, headers, correlation id, selectors), external clients connect over TCP exactly like a BW engine or the `jms-to-pubsub-bridge` (`JMS_PROVIDER=artemis`) | No EMS-specific features (EMS admin API, routes, FT store); no auth; non-persistent |
| **TIBCO BusinessWorks** `bw-pos-order-publisher` | `LegacyOrderPublisher` + `LegacyOrderGenerator` | Message shape (`oms.xsd`), header properties `storeId`/`eventType`/`orderType`/`JMSCorrelationID`, audit copy, order-type mix, alterations on tailored orders, event dates on rentals, cancel notices | Orders are generated, not received from stores; no XA, no file-drop polling |
| **IBM MQ** queue manager `QM1` | `icr.io/ibm-messaging/mq` (developer image) with `20-tb.mqsc` defining `ERP.ORDERS.IN` / `ERP.INVOICE.OUT` | Real MQ: channels, MQCSP auth, persistent queues, `MQRFH2` JMS headers, the exact `com.ibm.mq.jakarta.client` the bridges use | Single QM (no sender/receiver channels, no HA), dev channel `DEV.APP.SVRCONN` instead of a dedicated one |
| **ERP adapter** (Oracle EBS inbound interface) | `erp-mq-consumer` | Consumes with the MQ JMS client, handles `TextMessage` and `BytesMessage` (native MQ apps), parses the legacy XML, keeps counts per store, exposes what it booked | In-memory last 500 instead of EBS interface tables; no invoice flow back |
| **Oracle OMS** (WebLogic SOAP service + `OMSPRD`) | `legacy-oms-soap` | Contract-first WSDL from the same XSD, XSD validation before processing, SOAP faults with a typed `<Fault>` detail, idempotent `SubmitOrder` on `ORD_NBR`, Oracle DDL/SQL (`VARCHAR2`, `NUMBER`, `ORD_HDR_SEQ.NEXTVAL`, `DUAL`), status lifecycle, the `<Orders>` nightly extract written to GCS | H2 in-memory in Oracle mode instead of Oracle RAC; 2 tables instead of ~40; no PL/SQL packages |
| **Nightly extract** (`oms_extract.ksh`, SFTP) | `GET /admin/export`, `POST /admin/export-to-gcs` | Same `<Orders>` file format the Dataflow `DailyReconciliationPipeline` reads, one file per order date | On demand instead of cron (Cloud Scheduler can call it) |
| **Store POS** (1,200 registers) | `store-pos-simulator` | Realistic order mix, store numbers, correlation ids, Apigee `x-api-key`, both the new REST contract and the legacy SOAP envelope | 25 stores, uniform traffic |

## What to say in the interview

1. "I built the legacy side as runnable simulators so the migration could be rehearsed, not just drawn: an
   embedded Artemis broker stands in for EMS with the same destinations and header properties, the real IBM MQ
   container with the real MQ JMS client stands in for the ERP feed, and a contract-first SOAP service on an
   Oracle-mode database stands in for the OMS."
2. "The XML contract is one XSD. It generates the OMS JAXB classes with xjc, drives the WSDL, validates every SOAP
   request with a `PayloadValidatingInterceptor`, and both the Java broker and the Python POS simulator are tested
   against it - that is how I keep legacy producers, bridges and the new SOAP adapter from drifting."
3. "The OMS is idempotent on the order number because TIBCO BW redelivers: re-submitting returns the stored status
   with a DUPLICATE message. The same idea - `legacyMessageId` plus an inbox table - is what makes the bridges safe."
4. "The ERP consumer accepts `TextMessage` and `BytesMessage`: native MQ applications put bytes, JMS applications put
   text with an `MQRFH2`. Getting that detail wrong is the classic MQ migration surprise."
5. "IBM MQ runs as a StatefulSet in the `legacy` namespace with the queue definitions in an MQSC ConfigMap and the
   password in a Secret; the integration test spins up the same image with Testcontainers, so CI proves the client
   configuration (channel, MQCSP, reconnect options) before anything is deployed."
6. "The nightly extract is the batch interface: the OMS writes `<Orders>` XML to GCS, and the Dataflow batch job
   joins it with BigQuery `order_events` to produce the reconciliation that the migration exit criteria depend on."
7. "Everything is deliberately single-replica with in-memory state because these are test doubles, and the
   manifests say so - I would rather document a simplification than hide it."
8. "`JMS_PROVIDER=artemis|ibmmq` in the consumer and bridges means one code path, two brokers: local compose and CI use
   Artemis, GKE uses MQ - the same switch I would use for a real EMS-to-Pub/Sub cutover rehearsal."
