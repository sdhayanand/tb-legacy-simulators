package com.tailoredbrands.otd.legacyoms.admin;

import com.tailoredbrands.legacy.oms.xml.Orders;
import com.tailoredbrands.otd.legacyoms.domain.OrderRecord;
import com.tailoredbrands.otd.legacyoms.gcs.GcsExportWriter;
import com.tailoredbrands.otd.legacyoms.service.OmsService;
import com.tailoredbrands.otd.legacyoms.service.OmsException;
import com.tailoredbrands.otd.legacyoms.service.OrderNotFoundException;
import com.tailoredbrands.otd.legacyoms.xml.OmsXmlMarshaller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Tiny REST admin API - the equivalent of the DBA's SQL*Plus session and the nightly extract shell script.
 *
 * <ul>
 *   <li>{@code GET /admin/orders} - list orders (JSON)</li>
 *   <li>{@code GET /admin/orders/{orderNbr}} - one order</li>
 *   <li>{@code PUT /admin/orders/{orderNbr}/status?value=READY} - move an order along (demo helper)</li>
 *   <li>{@code GET /admin/export?date=yyyy-MM-dd} - the {@code <Orders>} XML extract for that order date</li>
 *   <li>{@code POST /admin/export-to-gcs?date=yyyy-MM-dd&bucket=...} - write the extract to GCS</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin")
public class OmsAdminController {

    private final OmsService service;
    private final OmsXmlMarshaller marshaller;
    private final GcsExportWriter gcsWriter;

    public OmsAdminController(OmsService service, OmsXmlMarshaller marshaller, GcsExportWriter gcsWriter) {
        this.service = service;
        this.marshaller = marshaller;
        this.gcsWriter = gcsWriter;
    }

    @GetMapping("/orders")
    public List<OrderRecord> listOrders(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return service.listOrders(limit);
    }

    @GetMapping("/orders/{orderNbr}")
    public OrderRecord getOrder(@PathVariable("orderNbr") String orderNbr) {
        return service.getOrder(orderNbr);
    }

    @PutMapping("/orders/{orderNbr}/status")
    public OrderRecord updateStatus(@PathVariable("orderNbr") String orderNbr,
                                    @RequestParam("value") String status) {
        return service.updateStatus(orderNbr, status.trim().toUpperCase());
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> export(
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate extractDate = date == null ? LocalDate.now() : date;
        Orders orders = service.exportOrders(extractDate);
        String xml = marshaller.toXml(orders);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header("Content-Disposition", "inline; filename=\"" + extractFileName(extractDate) + "\"")
                .header("X-Order-Count", String.valueOf(orders.getOrder().size()))
                .body(xml);
    }

    @PostMapping(value = "/export-to-gcs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> exportToGcs(
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("bucket") String bucket,
            @RequestParam(name = "prefix", defaultValue = "legacy-oms/extracts") String prefix) {
        LocalDate extractDate = date == null ? LocalDate.now() : date;
        Orders orders = service.exportOrders(extractDate);
        String xml = marshaller.toXml(orders);
        String objectName = stripSlashes(prefix) + "/" + extractFileName(extractDate);
        try {
            String uri = gcsWriter.write(bucket, objectName, xml);
            return ResponseEntity.ok(Map.of(
                    "status", "WRITTEN",
                    "uri", uri,
                    "extractDate", extractDate.toString(),
                    "orderCount", orders.getOrder().size()));
        } catch (GcsExportWriter.GcsUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "SKIPPED",
                    "reason", e.getMessage(),
                    "extractDate", extractDate.toString(),
                    "orderCount", orders.getOrder().size(),
                    "hint", "Set GOOGLE_APPLICATION_CREDENTIALS or run on GKE with Workload Identity; "
                            + "GET /admin/export?date=" + extractDate + " returns the same XML."));
        }
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(OrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(e));
    }

    @ExceptionHandler(OmsException.class)
    public ResponseEntity<Map<String, Object>> badRequest(OmsException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(e));
    }

    private static Map<String, Object> errorBody(OmsException e) {
        return Map.of(
                "code", e.getCode(),
                "message", e.getMessage() == null ? "" : e.getMessage(),
                "orderNbr", e.getOrderNbr() == null ? "" : e.getOrderNbr());
    }

    private static String extractFileName(LocalDate date) {
        return "orders-" + date + ".xml";
    }

    private static String stripSlashes(String prefix) {
        String p = prefix;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
