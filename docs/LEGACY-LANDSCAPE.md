# The legacy estate (fictional, but realistic)

> What the Order-to-Delivery migration starts from. Everything here is modelled, not copied, from how a
> ~1,200-store specialty retailer typically runs TIBCO + IBM MQ + Oracle integration. The simulators in this
> repo reproduce the *interfaces* (message shapes, queue names, SOAP contract, extract files, timing) so the new
> platform can be built and the migration rehearsed against them.

```
                 ┌──────────────────────────── TIBCO BusinessWorks 5.x (BW engines on 4 RHEL VMs) ───────────────────────────┐
 Store POS ──XML─▶ bw-pos-order-publisher ──▶ TIBCO EMS 8.x  TB.ORDERS.OUT ──▶ bw-oms-order-loader ──SOAP──▶ Oracle OMS (OMSPRD)
 (1,200 stores,    (file drop + HTTP)         (2 servers, FT pair)    │                                           │
  nightly + RT)                                TB.ORDERS.CANCEL ──────┤                                      ORD_HDR / ORD_LINE
                                               TB.ORDERS.AUDIT        └──▶ bw-erp-order-router ──▶ IBM MQ QM1  ERP.ORDERS.IN ──▶ ERP (Oracle EBS)
 E-com (ATG) ──SOAP──▶ bw-ecom-order-adapter ──▶ TB.ORDERS.OUT                                       ERP.INVOICE.OUT ◀── ERP
                                                                                                 ┌─ 01:30 nightly extract (ksh + SQL*Plus) ─▶ SFTP ─▶ Finance / Data warehouse
```

## 1. Messaging: TIBCO EMS

| Item | Value | Simulated by |
|---|---|---|
| EMS servers | `ems-prod-01` / `ems-prod-02`, fault-tolerant pair, `tcp://ems-prod:7222`, shared store on NFS | `ems-broker` (embedded Artemis, acceptor `tcp://0.0.0.0:61616`) |
| Destinations | `TB.ORDERS.OUT` (queue), `TB.ORDERS.CANCEL` (queue), `TB.ORDERS.AUDIT` (queue, 7-day retention, ops tails it), `ERP.ORDERS.IN` (EMS→MQ bridge staging) | `spring.artemis.embedded.queues` |
| Message format | `TextMessage`, UTF-8 XML, root `<Order xmlns="http://tailoredbrands.com/legacy/oms/v1">` | `LegacyOrderXmlWriter`, `samples/legacy-order-*.xml` |
| Header properties | `storeId`, `eventType` (`ORDER_CREATED`/`ORDER_CANCELLED`), `orderType` (R/T/C/X/E), `JMSCorrelationID` = `store-<store>-txn-<n>`, `JMSType=LegacyOrder` | `LegacyOrderPublisher.send` |
| Volume | 25-40k orders/day, peaks Sat 11:00-17:00 local and the week before prom season (April) and the holiday push (Nov) | `PUBLISH_RATE_PER_MIN`, `POST /simulate/orders?count=n` |
| Security | EMS users per BW engine, no TLS on the internal network, ACLs per destination | none (security disabled on the simulator) |

## 2. Integration logic: TIBCO BusinessWorks processes

| Process | Trigger | Does | Known pain |
|---|---|---|---|
| `bw-pos-order-publisher` | HTTP receiver (stores on a 3G/MPLS link) + polling of the store file drop (`/data/pos/<store>/*.xml`) for stores that batch overnight | Validates against `oms.xsd`, enriches with tailor-shop routing, publishes to `TB.ORDERS.OUT` + `TB.ORDERS.AUDIT` | Duplicate deliveries on retry (no idempotency key; OMS dedups on OrderNbr), 15-minute store batching hides real-time demand |
| `bw-oms-order-loader` | JMS queue receiver on `TB.ORDERS.OUT` (XA with EMS session) | Calls OMS `SubmitOrder` (SOAP 1.1 over HTTP), maps faults to a redelivery queue after 3 attempts | OMS is the bottleneck: 40-120 ms per call, serialised per store by design; backlog builds during peaks |
| `bw-erp-order-router` | JMS receiver on `TB.ORDERS.OUT` (second consumer) | Transforms `<Order>` to the ERP interface layout and puts it on IBM MQ `ERP.ORDERS.IN` (MQ JMS, `MQRFH2` on) | EMS→MQ "bridge" is really BW; outage of BW = ERP starves with no visibility |
| `bw-ecom-order-adapter` | SOAP receiver for the e-commerce platform | Wraps web orders in the same `<Order>` shape (store 9001) | Different timeouts/retries than the POS path, double-counting during incidents |
| `bw-oms-status-poller` | Timer every 5 min | `GetOrderStatus` for open tailored/custom orders, writes status to the store intranet DB | Polling, not events; store associates refresh the page |

## 3. Messaging: IBM MQ

| Item | Value | Simulated by |
|---|---|---|
| Queue managers | `QM1` (ERP side, AIX), `QMEMS` (integration side), MQ 9.2 LTS, sender/receiver channels between them | one `QM1` (`icr.io/ibm-messaging/mq`, dev image) |
| Queues | `ERP.ORDERS.IN` (local, persistent, max depth 100k), `ERP.INVOICE.OUT`, `ERP.ORDERS.DLQ`, `SYSTEM.DEAD.LETTER.QUEUE` | `deploy/k8s/ibm-mq/20-tb.mqsc` |
| Channel / auth | `ERP.APP.SVRCONN`, MQCSP user/password, CHLAUTH by IP range | `DEV.APP.SVRCONN`, user `app` |
| Consumer | ERP adapter (Oracle EBS, WMQ JMS 2.0, `MQRFH2`): parses `<Order>` and books the sales order | `erp-mq-consumer` (`GET /received`) |

## 4. Oracle OMS

| Item | Value | Simulated by |
|---|---|---|
| Database | Oracle 19c RAC, DB `OMSPRD`, schema `OMS_OWNER` | H2 `MODE=Oracle` (`jdbc:h2:mem:oms;MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH`) |
| Tables | `ORD_HDR` (order header), `ORD_LINE`, `ORD_ALT` (folded into `ORD_LINE` here), `ORD_STATUS_HIST`, `STORE`, `TAILOR_SHOP` | `schema.sql` (`ORD_HDR`, `ORD_LINE`, `ORD_HDR_SEQ`) |
| Statuses | `NEW → RELEASED → IN_ALTERATION → READY → SHIPPED → COMPLETE`, `CANCELLED` | `StatusCode` enum, `PUT /admin/orders/{nbr}/status` |
| SOAP service | `OmsOrderService` on WebLogic, WSDL `http://oms-prod:7001/oms/ws/OmsOrderService?wsdl`, ops `SubmitOrder`, `GetOrderStatus`, `ExportOrders` | `legacy-oms-soap` (`/ws/oms.wsdl`) |
| Idempotency | Unique index on `ORD_NBR`; re-submits return the existing status with "DUPLICATE" | `OmsService.submitOrder` |

## 5. Nightly batch window

| Time (PT) | Job | Simulated by |
|---|---|---|
| 00:30 | Store close files land on the SFTP drop; `bw-pos-order-publisher` drains them | `POST /simulate/orders?count=200` |
| 01:30 | `oms_extract.ksh`: SQL*Plus spool of the day's orders to `orders-YYYY-MM-DD.xml` (`<Orders>` root), scp to the finance SFTP | `GET /admin/export?date=` and `POST /admin/export-to-gcs?date=&bucket=` |
| 02:00 | Finance / DW load; the reconciliation report (OMS vs ERP counts and amounts) is e-mailed at 06:00 | Dataflow `DailyReconciliationPipeline` reads the GCS extract and joins it with BigQuery `otd.order_events` |
| 03:00 | EMS `TB.ORDERS.AUDIT` purge beyond 7 days, MQ `ERP.ORDERS.DLQ` report | — |

## 6. Pain points that motivate the migration

1. **No visibility** - counts live in BW engine logs, EMS admin tool and MQ Explorer; reconciliation is a nightly
   e-mail. The platform's answer: every event in BigQuery (`otd.order_events`), Pub/Sub metrics, a reconciler.
2. **Point-to-point coupling** - adding a consumer (e.g. the tailor-shop app) means a new BW process and EMS
   destination plus a change ticket. Pub/Sub topics + subscriptions with schemas make fan-out a config change.
3. **Duplicates and ordering** - BW redeliveries create duplicates; two BW consumers on `TB.ORDERS.OUT` process in
   different orders. The platform uses `eventId`/`legacyMessageId` + an inbox table and ordering keys (`storeId`).
4. **Capacity and cost** - EMS/MQ/BW licences and VM patching; the peaks (prom, holidays) are provisioned for all
   year. Pub/Sub + GKE Autopilot + Dataflow scale per message.
5. **Opaque XML contracts** - `oms.xsd` is shared by copy-paste; schema drift is found in production. Pub/Sub
   schemas (proto) with revisions and an additive-only rule; XSLT at the edge for legacy producers.
6. **Risky cutover** - no way to run old and new side by side. The bridges (`jms-to-pubsub-bridge`,
   `pubsub-to-jms-bridge`), `MIGRATION_PHASE` and the reconciler make a `SHADOW → DUAL_RUN → PUBSUB_PRIMARY → CUTOVER`
   path possible, with the simulators in this repo as the "legacy" side during rehearsals.

## 7. Where each legacy concept lands in the new platform

| Legacy | Target |
|---|---|
| EMS queue `TB.ORDERS.OUT` | Pub/Sub topic `orders-v1` (ordering key `storeId`) |
| EMS queue `TB.ORDERS.AUDIT` | BigQuery subscription `orders-bq-archive` → `otd.orders_raw` |
| EMS queue `TB.ORDERS.CANCEL` | `orders-v1` with `eventType=ORDER_CANCELLED` |
| MQ `ERP.ORDERS.IN` | kept until phase 3 via `pubsub-to-jms-bridge` (`orders-to-legacy-mq` subscription) |
| BW `bw-oms-order-loader` | `order-intake-api` SOAP adapter (`/ws/orders`) + outbox |
| Nightly extract + reconciliation e-mail | Dataflow batch `daily-reconciliation` → `otd.order_reconciliation` |
| EMS/MQ dead-letter handling | `events-dlq` topic, subscription dead-letter policies (5 attempts), alerting |
