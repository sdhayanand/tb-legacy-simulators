package com.tailoredbrands.otd.legacyoms.repository;

import com.tailoredbrands.otd.legacyoms.domain.OrderLineRecord;
import com.tailoredbrands.otd.legacyoms.domain.OrderRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Plain-JDBC access to the Oracle-style {@code ORD_HDR} / {@code ORD_LINE} tables.
 *
 * <p>The SQL is written the way the real OMS PL/SQL packages would write it (sequence {@code .NEXTVAL},
 * {@code DUAL}, upper-case identifiers) and runs unchanged on H2 in {@code MODE=Oracle}.
 */
@Repository
public class OrderRepository {

    private static final String HDR_COLUMNS =
            "ORD_ID, ORD_NBR, ORD_TYPE, STORE_NBR, CUST_NBR, ORD_DATE, EVENT_DATE, STATUS, TOTAL_AMT, CREATED_TS, UPDATED_TS";

    private static final String LINE_COLUMNS =
            "LINE_NBR, SKU, QTY, PRICE, FULFILL_TYPE, ALT_TYPE, ALT_MEASUREMENT_IN, TAILOR_SHOP_NBR";

    private static final RowMapper<OrderRecord> HEADER_MAPPER = (rs, rowNum) -> new OrderRecord(
            rs.getLong("ORD_ID"),
            rs.getString("ORD_NBR"),
            rs.getString("ORD_TYPE"),
            rs.getString("STORE_NBR"),
            rs.getString("CUST_NBR"),
            toLocalDate(rs.getTimestamp("ORD_DATE")),
            toLocalDate(rs.getTimestamp("EVENT_DATE")),
            rs.getString("STATUS"),
            rs.getBigDecimal("TOTAL_AMT"),
            toInstant(rs.getTimestamp("CREATED_TS")),
            toInstant(rs.getTimestamp("UPDATED_TS")),
            List.of());

    private static final RowMapper<OrderLineRecord> LINE_MAPPER = (rs, rowNum) -> new OrderLineRecord(
            rs.getInt("LINE_NBR"),
            rs.getString("SKU"),
            rs.getInt("QTY"),
            rs.getBigDecimal("PRICE"),
            rs.getString("FULFILL_TYPE"),
            rs.getString("ALT_TYPE"),
            rs.getBigDecimal("ALT_MEASUREMENT_IN"),
            rs.getString("TAILOR_SHOP_NBR"));

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextOrderId() {
        Long next = jdbc.queryForObject("SELECT ORD_HDR_SEQ.NEXTVAL FROM DUAL", Long.class);
        if (next == null) {
            throw new IllegalStateException("ORD_HDR_SEQ returned no value");
        }
        return next;
    }

    public boolean existsByOrderNbr(String orderNbr) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORD_HDR WHERE ORD_NBR = ?", Integer.class, orderNbr);
        return count != null && count > 0;
    }

    public Optional<OrderRecord> findByOrderNbr(String orderNbr) {
        List<OrderRecord> headers = jdbc.query(
                "SELECT " + HDR_COLUMNS + " FROM ORD_HDR WHERE ORD_NBR = ?", HEADER_MAPPER, orderNbr);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withLines(headers.get(0)));
    }

    public List<OrderRecord> findAll(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        List<OrderRecord> headers = jdbc.query(
                "SELECT " + HDR_COLUMNS + " FROM ORD_HDR ORDER BY ORD_DATE DESC, ORD_ID DESC FETCH FIRST "
                        + safeLimit + " ROWS ONLY",
                HEADER_MAPPER);
        return headers.stream().map(this::withLines).toList();
    }

    /** Orders whose {@code ORD_DATE} falls in [from, to] (both inclusive, whole days). */
    public List<OrderRecord> findByOrderDateBetween(LocalDate from, LocalDate to) {
        List<OrderRecord> headers = jdbc.query(
                "SELECT " + HDR_COLUMNS + " FROM ORD_HDR WHERE ORD_DATE >= ? AND ORD_DATE < ? ORDER BY ORD_ID",
                HEADER_MAPPER,
                Timestamp.valueOf(from.atStartOfDay()),
                Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        return headers.stream().map(this::withLines).toList();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ORD_HDR", Long.class);
        return count == null ? 0L : count;
    }

    @Transactional
    public void insert(OrderRecord order) {
        jdbc.update("INSERT INTO ORD_HDR (" + HDR_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.ordId(),
                order.orderNbr(),
                order.orderType(),
                order.storeNbr(),
                order.custNbr(),
                Timestamp.valueOf(order.orderDate().atStartOfDay()),
                order.eventDate() == null ? null : Timestamp.valueOf(order.eventDate().atStartOfDay()),
                order.status(),
                order.totalAmt(),
                Timestamp.from(order.createdTs()),
                Timestamp.from(order.updatedTs()));

        for (OrderLineRecord line : order.lines()) {
            jdbc.update("INSERT INTO ORD_LINE (ORD_ID, " + LINE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    order.ordId(),
                    line.lineNbr(),
                    line.sku(),
                    line.qty(),
                    line.price(),
                    line.fulfillType(),
                    line.altType(),
                    line.altMeasurementInches(),
                    line.tailorShopNbr());
        }
    }

    @Transactional
    public int updateStatus(String orderNbr, String status) {
        return jdbc.update("UPDATE ORD_HDR SET STATUS = ?, UPDATED_TS = ? WHERE ORD_NBR = ?",
                status, Timestamp.from(Instant.now()), orderNbr);
    }

    private OrderRecord withLines(OrderRecord header) {
        List<OrderLineRecord> lines = jdbc.query(
                "SELECT " + LINE_COLUMNS + " FROM ORD_LINE WHERE ORD_ID = ? ORDER BY LINE_NBR",
                LINE_MAPPER, header.ordId());
        return header.withLines(lines);
    }

    private static LocalDate toLocalDate(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().toLocalDate();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
